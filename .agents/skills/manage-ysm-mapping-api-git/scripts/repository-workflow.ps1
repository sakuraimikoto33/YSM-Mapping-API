[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet("Inspect", "Classify", "PrepareMinecraftBranch", "Validate", "Snapshot",
        "CompareSnapshot", "Commit", "MergeMain", "Push", "Audit")]
    [string]$Operation,
    [string]$RepoRoot = "",
    [string[]]$Path = @(),
    [string]$ExpectedBranch = "",
    [string]$Message = "",
    [ValidateSet("ExplicitUser", "TaskBoundary")]
    [string]$Authorization,
    [switch]$ConfirmExecution,
    [switch]$ContractVersionAuthorized,
    [string]$DependencyVersionReason = "",
    [string]$MinecraftVersion = "",
    [string]$BaseBranch = "",
    [string]$SnapshotPath = "",
    [string]$FinalBranch = "",
    [string]$Remote = "",
    [string]$RefSpec = "",
    [ValidateSet("None", "ForceWithLease", "Force")]
    [string]$ForceMode = "None",
    [string]$ForceAuthorization = "",
    [switch]$AllowDirty,
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-Git {
    param([Parameter(Mandatory)][string[]]$Arguments, [string]$Root = $script:RepositoryRoot,
        [switch]$AllowFailure)
    $start = [Diagnostics.ProcessStartInfo]::new("git")
    $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    [void]$start.ArgumentList.Add("-C")
    [void]$start.ArgumentList.Add($Root)
    foreach ($argument in $Arguments) { [void]$start.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    [void]$process.Start()
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    $text = $stdout.GetAwaiter().GetResult().TrimEnd([char[]]"`r`n")
    $errorText = $stderr.GetAwaiter().GetResult().TrimEnd([char[]]"`r`n")
    $lines = if ($text) { @($text -split "`r?`n") } else { @() }
    if ($process.ExitCode -ne 0 -and -not $AllowFailure) {
        throw "git $($Arguments -join ' ') failed ($($process.ExitCode)): $errorText $text"
    }
    [pscustomobject]@{ ExitCode = $process.ExitCode; Lines = $lines; Text = $text; ErrorText = $errorText }
}
function Assert-PolicyKeys {
    param([Collections.IDictionary]$Data, [string[]]$Required, [string]$Label)
    $missing = @($Required | Where-Object { -not $Data.Contains($_) })
    $unknown = @($Data.Keys | Where-Object { $_ -notin $Required })
    if ($missing.Count) { throw "$Label is missing required keys: $($missing -join ', ')." }
    if ($unknown.Count) { throw "$Label contains unknown keys: $($unknown -join ', ')." }
}
function Assert-RelativePolicyPath {
    param([string]$Value, [string]$Label, [switch]$AllowPrefix)
    $normalized = $Value.Replace("\", "/").Trim()
    if (-not $normalized -or [IO.Path]::IsPathRooted($normalized) -or $normalized -eq "." -or
        $normalized -match '(^|/)\.\.(/|$)' -or $normalized.IndexOfAny([char[]]"*?[") -ge 0 -or
        $normalized.StartsWith(":") -or (-not $AllowPrefix -and $normalized.EndsWith("-"))) {
        throw "$Label contains an unsafe repository-relative path: '$Value'."
    }
    $normalized
}
function Load-RepositoryPolicy {
    $corePath = Join-Path $PSScriptRoot "repository-policy.psd1"
    $profilePath = Join-Path $script:RepositoryRoot ".agents/repository-profile.psd1"
    if (-not (Test-Path -LiteralPath $profilePath -PathType Leaf)) { throw "Repository profile missing: $profilePath" }
    $core = Import-PowerShellDataFile -LiteralPath $corePath
    $profile = Import-PowerShellDataFile -LiteralPath $profilePath
    $coreKeys = @("MainBranch", "MinecraftBranchPattern", "ContractVersionPatterns",
        "DependencyVersionPatterns", "MainValidation", "MinecraftValidation")
    $profileKeys = @("Name", "SharedPaths", "VersionPaths", "MixedPaths",
        "ForbiddenTrackedPatterns", "RepositoryVerifier")
    Assert-PolicyKeys -Data $core -Required $coreKeys -Label "Core repository policy"
    Assert-PolicyKeys -Data $profile -Required $profileKeys -Label "Repository profile"
    if ([string]::IsNullOrWhiteSpace([string]$profile.Name)) { throw "Repository profile Name must not be empty." }
    if ([string]$core.MainBranch -ne "main") { throw "Core repository policy must use main as MainBranch." }
    try { [void][regex]::new([string]$core.MinecraftBranchPattern) }
    catch { throw "Invalid MinecraftBranchPattern: $($_.Exception.Message)" }
    foreach ($property in @("ContractVersionPatterns", "DependencyVersionPatterns")) {
        foreach ($pattern in @($core[$property])) {
            try { [void][regex]::new([string]$pattern) }
            catch { throw "Invalid $property regex '$pattern': $($_.Exception.Message)" }
        }
    }
    foreach ($pattern in @($profile.ForbiddenTrackedPatterns)) {
        try { [void][regex]::new([string]$pattern) }
        catch { throw "Invalid ForbiddenTrackedPatterns regex '$pattern': $($_.Exception.Message)" }
    }
    $seen = @{}
    foreach ($category in @("SharedPaths", "VersionPaths", "MixedPaths")) {
        if (-not @($profile[$category]).Count) { throw "Repository profile $category must not be empty." }
        foreach ($value in @($profile[$category])) {
            $normalized = Assert-RelativePolicyPath -Value ([string]$value) -Label $category -AllowPrefix
            $key = $normalized.ToLowerInvariant()
            if ($seen.ContainsKey($key)) {
                throw "Ownership path '$normalized' appears in both $($seen[$key]) and $category."
            }
            $seen[$key] = $category
        }
    }
    $verifier = Assert-RelativePolicyPath -Value ([string]$profile.RepositoryVerifier) -Label "RepositoryVerifier"
    $verifierPath = [IO.Path]::GetFullPath((Join-Path $script:RepositoryRoot $verifier))
    $rootPrefix = [IO.Path]::GetFullPath($script:RepositoryRoot).TrimEnd([IO.Path]::DirectorySeparatorChar) +
        [IO.Path]::DirectorySeparatorChar
    if (-not $verifierPath.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase) -or
        -not (Test-Path -LiteralPath $verifierPath -PathType Leaf)) {
        throw "RepositoryVerifier is missing or escapes the repository: $verifier"
    }
    $merged = [ordered]@{}
    foreach ($key in $coreKeys) { $merged[$key] = $core[$key] }
    foreach ($key in $profileKeys) { $merged[$key] = $profile[$key] }
    $merged
}

if (-not $RepoRoot) {
    $root = @(& git rev-parse --show-toplevel 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "Not inside a Git worktree: $($root -join [Environment]::NewLine)" }
    $RepoRoot = "$($root[-1])"
}
$script:RepositoryRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
$reported = (Invoke-Git -Arguments @("rev-parse", "--show-toplevel")).Text.Trim()
if ([IO.Path]::GetFullPath($reported) -ne [IO.Path]::GetFullPath($script:RepositoryRoot)) {
    throw "Repository root mismatch: expected '$script:RepositoryRoot', found '$reported'."
}
function Write-Result { param($Value) $Value | ConvertTo-Json -Depth 14 -Compress }
try {
    $script:Policy = Load-RepositoryPolicy
    $script:LogRoot = Join-Path ([IO.Path]::GetTempPath()) (($script:Policy.Name -replace '[^A-Za-z0-9.-]', '-') + "-workflow")
} catch {
    Write-Result -Value ([ordered]@{ operation = $Operation; success = $false; error = $_.Exception.Message })
    exit 1
}
function Current-Branch {
    param([string]$Root = $script:RepositoryRoot)
    $value = Invoke-Git -Root $Root -Arguments @("symbolic-ref", "--quiet", "--short", "HEAD") -AllowFailure
    if ($value.ExitCode -eq 0) { return $value.Text.Trim() }
    return $null
}
function Branch-Exists {
    param([Parameter(Mandatory)][string]$Name)
    (Invoke-Git -Arguments @("show-ref", "--verify", "--quiet", "refs/heads/$Name") -AllowFailure).ExitCode -eq 0
}
function Assert-MainExists {
    $main = [string]$script:Policy.MainBranch
    if (-not (Branch-Exists -Name $main)) { throw "Required branch '$main' is missing." }
}
function Minecraft-Branches {
    @((Invoke-Git -Arguments @("for-each-ref", "--format=%(refname:short)", "refs/heads")).Lines |
        Where-Object { $_ -match [string]$script:Policy.MinecraftBranchPattern } | Sort-Object -Unique)
}
function Pending-State {
    param([string]$Root = $script:RepositoryRoot)
    $staged = @((Invoke-Git -Root $Root -Arguments @("diff", "--cached", "--name-only")).Lines | Where-Object { $_ })
    $unstaged = @((Invoke-Git -Root $Root -Arguments @("diff", "--name-only")).Lines | Where-Object { $_ })
    $untracked = @((Invoke-Git -Root $Root -Arguments @("ls-files", "--others", "--exclude-standard")).Lines | Where-Object { $_ })
    [pscustomobject]@{ Staged = $staged; Unstaged = $unstaged; Untracked = $untracked;
        All = @($staged + $unstaged + $untracked | Sort-Object -Unique) }
}
function In-Progress {
    param([string]$Root = $script:RepositoryRoot)
    $found = [Collections.Generic.List[string]]::new()
    foreach ($name in @("MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "BISECT_LOG", "rebase-merge", "rebase-apply")) {
        $gitPath = (Invoke-Git -Root $Root -Arguments @("rev-parse", "--git-path", $name)).Text.Trim()
        if (-not [IO.Path]::IsPathRooted($gitPath)) { $gitPath = Join-Path $Root $gitPath }
        if (Test-Path -LiteralPath $gitPath) { $found.Add($name) }
    }
    @($found)
}
function Assert-StableGit {
    $progress = @(In-Progress)
    if ($progress.Count) { throw "Git operation already in progress: $($progress -join ', ')." }
}
function Normalize-Paths {
    param([Parameter(Mandatory)][string[]]$Values)
    if (-not $Values.Count) { throw "At least one exact repository-relative -Path is required." }
    $result = [Collections.Generic.List[string]]::new()
    foreach ($item in $Values) {
        $value = $item.Replace("\", "/").Trim()
        while ($value.StartsWith("./")) { $value = $value.Substring(2) }
        $value = $value.TrimEnd("/")
        if (-not $value -or [IO.Path]::IsPathRooted($value) -or $value -eq "." -or
            $value -match '(^|/)\.\.(/|$)' -or $value.IndexOfAny([char[]]"*?[") -ge 0 -or
            $value.StartsWith(":")) { throw "Unsafe or non-exact repository path: '$item'." }
        $result.Add($value)
    }
    @($result | Sort-Object -Unique)
}
function In-Scope {
    param([string]$Candidate, [string[]]$Scopes)
    foreach ($scope in $Scopes) {
        if ($Candidate -eq $scope -or $Candidate.StartsWith("$scope/", [StringComparison]::Ordinal)) { return $true }
    }
    return $false
}
function Policy-Category {
    param([Parameter(Mandatory)][string]$Candidate)
    foreach ($pattern in @($script:Policy.ForbiddenTrackedPatterns)) {
        if ($Candidate -match $pattern) { return "Forbidden" }
    }
    foreach ($prefix in @($script:Policy.SharedPaths)) {
        if ($Candidate -eq $prefix -or $Candidate.StartsWith("$prefix/", [StringComparison]::Ordinal) -or
            ($prefix.EndsWith("-") -and $Candidate.StartsWith($prefix, [StringComparison]::Ordinal))) { return "Shared" }
    }
    foreach ($prefix in @($script:Policy.VersionPaths)) {
        if ($Candidate -eq $prefix -or $Candidate.StartsWith("$prefix/", [StringComparison]::Ordinal) -or
            ($prefix.EndsWith("-") -and $Candidate.StartsWith($prefix, [StringComparison]::Ordinal))) { return "Minecraft" }
    }
    foreach ($prefix in @($script:Policy.MixedPaths)) {
        if ($Candidate -eq $prefix -or $Candidate.StartsWith("$prefix/", [StringComparison]::Ordinal)) { return "Mixed" }
    }
    return "Unknown"
}
function Contract-Version-Lines {
    $diff = (Invoke-Git -Arguments @("diff", "--no-ext-diff", "--unified=0", "HEAD", "--")).Lines
    $findings = [Collections.Generic.List[string]]::new()
    foreach ($line in $diff) {
        if ($line.StartsWith("+++")) { continue }
        foreach ($pattern in @($script:Policy.ContractVersionPatterns)) {
            if ($line -match $pattern) { $findings.Add($line); break }
        }
    }
    @($findings)
}
function Assert-Contract-Version {
    $matches = @(Contract-Version-Lines)
    if ($matches.Count -and -not $ContractVersionAuthorized) {
        throw "Product contract version changes require explicit authorization: $($matches -join ' | ')"
    }
    @($matches)
}
function Dependency-Version-Lines {
    $diff = (Invoke-Git -Arguments @("diff", "--no-ext-diff", "--unified=0", "HEAD", "--")).Lines
    $findings = [Collections.Generic.List[string]]::new()
    foreach ($line in $diff) {
        if ($line.StartsWith("+++")) { continue }
        foreach ($pattern in @($script:Policy.DependencyVersionPatterns)) {
            if ($line -match $pattern) { $findings.Add($line); break }
        }
    }
    @($findings)
}
function Assert-Dependency-Version {
    $findings = @(Dependency-Version-Lines)
    if ($findings.Count -and [string]::IsNullOrWhiteSpace($DependencyVersionReason)) {
        throw "Dependency version changes require a recorded necessity in -DependencyVersionReason: $($findings -join ' | ')"
    }
    [ordered]@{ lines = $findings; reason = if ($findings.Count) { $DependencyVersionReason.Trim() } else { "" } }
}
function Assert-Minecraft-Version {
    $findings = [Collections.Generic.List[object]]::new()
    foreach ($line in (Invoke-Git -Arguments @("diff", "--no-ext-diff", "--unified=0", "HEAD", "--")).Lines) {
        if ($line -match '^\+(?!\+).*\b(?:minecraftVersion|minecraft_version)\s*=\s*[`"'']?([0-9A-Za-z][0-9A-Za-z._+-]*)') {
            $findings.Add([ordered]@{ line = $line; version = $Matches[1] })
        }
    }
    $versions = @($findings | ForEach-Object version | Sort-Object -Unique)
    if ($versions.Count) {
        $expected = @($versions | ForEach-Object { "mc/$_" })
        if ($versions.Count -ne 1 -or (Current-Branch) -ne $expected[0]) {
            throw "Minecraft version changes belong on their matching branch: expected=$($expected -join ', '), current=$(Current-Branch)."
        }
    }
    @($findings)
}
function Assert-Policy {
    param([switch]$PermitDirty)
    Assert-MainExists
    $main = [string]$script:Policy.MainBranch
    $mc = @(Minecraft-Branches)
    foreach ($branch in $mc) {
        if ((Invoke-Git -Arguments @("merge-base", "--is-ancestor", $main, $branch) -AllowFailure).ExitCode -ne 0) {
            throw "$main is not an ancestor of $branch."
        }
        $shared = @(Normalize-Paths -Values @($script:Policy.SharedPaths | Where-Object { -not $_.EndsWith('-') }))
        $different = Invoke-Git -Arguments (@("diff", "--quiet", "$main..$branch", "--") + $shared) -AllowFailure
        if ($different.ExitCode -eq 1) { throw "Shared paths differ between $main and $branch." }
        if ($different.ExitCode -gt 1) { throw "Unable to compare shared paths for ${branch}: $($different.ErrorText)" }
    }
    $badMerge = @((Invoke-Git -Arguments @("log", "--first-parent", "--merges", "--format=%H%x09%s", $main)).Lines |
        Where-Object { $_ -match '(?i)merge.*mc/' })
    if ($badMerge.Count) { throw "$main contains a merge that names an mc/* branch: $($badMerge -join ' | ')" }
    foreach ($tracked in (Invoke-Git -Arguments @("ls-files")).Lines) {
        foreach ($pattern in @($script:Policy.ForbiddenTrackedPatterns)) {
            if ($tracked -match $pattern) { throw "Forbidden tracked path: $tracked" }
        }
    }
    $minecraftChanges = @(Assert-Minecraft-Version)
    $contract = @(Assert-Contract-Version)
    $dependencies = Assert-Dependency-Version
    $dirty = (Pending-State).All
    if ($dirty.Count -and -not $PermitDirty) { throw "Working tree is dirty: $($dirty -join ', ')." }
    [ordered]@{ mainExists = $true; minecraftBranches = $mc;
        contractVersionLines = $contract; dependencyVersionLines = @($dependencies.lines);
        dependencyVersionReason = $dependencies.reason; minecraftVersionLines = @($minecraftChanges) }
}
function New-Log {
    param([string]$Label)
    [void](New-Item -ItemType Directory -Force -Path $script:LogRoot)
    Join-Path $script:LogRoot ("{0}-{1}-{2}.log" -f (Get-Date -Format "yyyyMMdd-HHmmss-fff"), $Label,
        [guid]::NewGuid().ToString("N"))
}
function Run-DomainVerifier {
    param([string]$Root)
    $path = Join-Path $Root ([string]$script:Policy.RepositoryVerifier)
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Repository verifier missing: $path" }
    $log = New-Log -Label "domain-policy"
    & $path -RepoRoot $Root -AllowContractVersionChange:$ContractVersionAuthorized *> $log
    $succeeded = $?
    if (-not $succeeded) { throw "Domain policy failed. log=$log" }
    return $log
}
function Run-Build {
    param([string]$Root, [string]$Profile)
    if ($SkipBuild) { return $null }
    $wrapper = Join-Path $Root "gradlew.bat"
    if (-not (Test-Path -LiteralPath $wrapper -PathType Leaf)) { throw "Gradle wrapper missing: $wrapper" }
    $tasks = if ($Profile -eq "Main") { @($script:Policy.MainValidation) }
        elseif ($Profile -eq "Minecraft") { @($script:Policy.MinecraftValidation) }
        else { throw "Unknown validation profile: $Profile" }
    $log = New-Log -Label "$Profile-build"
    Push-Location -LiteralPath $Root
    try {
        & $wrapper @tasks --no-daemon --no-parallel --no-problems-report *> $log
        $code = $LASTEXITCODE
    } finally { Pop-Location }
    if ($code -ne 0) { throw "Gradle validation failed ($code). log=$log" }
    return $log
}
function Run-Validation {
    param(
        [string]$Root = $script:RepositoryRoot,
        [switch]$PermitDirty,
        [switch]$SkipRepositoryPolicy,
        [string]$ProfileOverride = ""
    )
    foreach ($args in @(@("diff", "--check"), @("diff", "--cached", "--check"))) {
        $check = Invoke-Git -Root $Root -Arguments $args -AllowFailure
        if ($check.ExitCode -ne 0) { throw "Whitespace validation failed: $($check.Text)" }
    }
    $policy = if ($SkipRepositoryPolicy) { $null } else { Assert-Policy -PermitDirty:$PermitDirty }
    $branch = Current-Branch -Root $Root
    $profile = if ($ProfileOverride) { $ProfileOverride }
        elseif ($branch -eq [string]$script:Policy.MainBranch) { "Main" }
        elseif ($branch -and $branch -match [string]$script:Policy.MinecraftBranchPattern) { "Minecraft" }
        else { throw "Unsupported branch '$branch'." }
    $logs = [Collections.Generic.List[string]]::new()
    if (-not $SkipRepositoryPolicy) { $domain = Run-DomainVerifier -Root $Root; if ($domain) { $logs.Add($domain) } }
    $build = Run-Build -Root $Root -Profile $profile; if ($build) { $logs.Add($build) }
    [ordered]@{ profile = $profile; policy = $policy; logs = @($logs) }
}
function Inspect-Result {
    $pending = Pending-State
    $branches = @((Invoke-Git -Arguments @("for-each-ref", "--format=%(refname:short)%09%(objectname)%09%(upstream:short)",
        "refs/heads")).Lines | ForEach-Object {
            $parts = $_ -split "`t", 3
            [ordered]@{ name = $parts[0]; head = $parts[1]; upstream = if ($parts.Count -gt 2) { $parts[2] } else { "" } }
        })
    [ordered]@{ operation = "Inspect"; repository = $script:RepositoryRoot; policy = $script:Policy.Name;
        branch = Current-Branch; head = (Invoke-Git -Arguments @("rev-parse", "HEAD")).Text.Trim(); dirty = [bool]$pending.All.Count;
        staged = @($pending.Staged); unstaged = @($pending.Unstaged); untracked = @($pending.Untracked); branches = $branches;
        minecraftBranches = @(Minecraft-Branches); mainExists = (Branch-Exists -Name ([string]$script:Policy.MainBranch));
        remotes = @((Invoke-Git -Arguments @("remote", "-v")).Lines);
        worktrees = @((Invoke-Git -Arguments @("worktree", "list", "--porcelain")).Lines); inProgress = @(In-Progress) }
}
function Classify-Result {
    $scopes = Normalize-Paths -Values $Path
    $items = @($scopes | ForEach-Object { [ordered]@{ path = $_; category = Policy-Category -Candidate $_ } })
    [ordered]@{ operation = "Classify"; paths = $items; requiresSemanticReview = [bool]@($items |
        Where-Object { $_.category -in @("Mixed", "Unknown") }).Count }
}
function Prepare-MinecraftBranch {
    if ($MinecraftVersion -notmatch '^[0-9A-Za-z][0-9A-Za-z._+-]*$') { throw "A safe -MinecraftVersion is required." }
    Assert-MainExists
    Assert-StableGit
    $pending = Pending-State
    if ($pending.All.Count) { throw "Branch preparation requires a clean worktree: $($pending.All -join ', ')." }
    $target = "mc/$MinecraftVersion"
    if (Branch-Exists -Name $target) {
        if ($ConfirmExecution) {
            if ($Authorization -ne "ExplicitUser") { throw "Branch switching requires Authorization=ExplicitUser." }
            [void](Invoke-Git -Arguments @("switch", $target))
        }
        return [ordered]@{ operation = "PrepareMinecraftBranch"; target = $target; exists = $true;
            switched = [bool]$ConfirmExecution; branch = Current-Branch }
    }
    $candidates = @((Invoke-Git -Arguments @("for-each-ref", "--format=%(refname:short)%09%(objectname)", "refs/heads")).Lines)
    if (-not $BaseBranch) {
        return [ordered]@{ operation = "PrepareMinecraftBranch"; target = $target; exists = $false;
            requiresBaseSelection = $true; candidates = $candidates }
    }
    if (-not (Branch-Exists -Name $BaseBranch)) { throw "Selected base branch does not exist: $BaseBranch" }
    if (-not $ConfirmExecution) {
        return [ordered]@{ operation = "PrepareMinecraftBranch"; target = $target; exists = $false;
            requiresConfirmation = $true; base = $BaseBranch; candidates = $candidates }
    }
    if ($Authorization -ne "ExplicitUser") { throw "Branch creation requires Authorization=ExplicitUser." }
    $baseTip = (Invoke-Git -Arguments @("rev-parse", $BaseBranch)).Text.Trim()
    $main = [string]$script:Policy.MainBranch
    $mainTip = (Invoke-Git -Arguments @("rev-parse", $main)).Text.Trim()
    $needsMain = (Invoke-Git -Arguments @("merge-base", "--is-ancestor", $main, $BaseBranch) -AllowFailure).ExitCode -ne 0
    $expectedTree = (Invoke-Git -Arguments @("rev-parse", "$baseTip^{tree}")).Text.Trim()
    if ($needsMain) {
        $temp = Join-Path ([IO.Path]::GetTempPath()) ("mc-branch-preflight-" + [guid]::NewGuid().ToString("N"))
        [void](Invoke-Git -Arguments @("worktree", "add", "--detach", $temp, $BaseBranch))
        try {
            $merge = Invoke-Git -Root $temp -Arguments @("merge", "--no-ff", "--no-commit", $main) -AllowFailure
            if ($merge.ExitCode -ne 0) { throw "main merge preflight failed: $($merge.Text) $($merge.ErrorText)" }
            $expectedTree = (Invoke-Git -Root $temp -Arguments @("write-tree")).Text.Trim()
        } finally {
            [void](Invoke-Git -Root $temp -Arguments @("merge", "--abort") -AllowFailure)
            [void](Invoke-Git -Arguments @("worktree", "remove", "--force", $temp) -AllowFailure)
        }
    }
    if (Branch-Exists -Name $target) { throw "Target branch appeared during preflight: $target" }
    if ((Invoke-Git -Arguments @("rev-parse", $BaseBranch)).Text.Trim() -ne $baseTip) { throw "Base branch moved during preflight: $BaseBranch" }
    if ((Invoke-Git -Arguments @("rev-parse", $main)).Text.Trim() -ne $mainTip) { throw "$main moved during preflight." }
    [void](Invoke-Git -Arguments @("switch", "-c", $target, $baseTip))
    if ($needsMain) { [void](Invoke-Git -Arguments @("merge", "--no-ff", $mainTip, "-m", "Merge branch 'main' into $target")) }
    $actualTree = (Invoke-Git -Arguments @("rev-parse", "HEAD^{tree}")).Text.Trim()
    if ($actualTree -ne $expectedTree) { throw "Created branch tree differs from preflight for $target." }
    [ordered]@{ operation = "PrepareMinecraftBranch"; target = $target; exists = $false; created = $true;
        base = $BaseBranch; baseTip = $baseTip; mainTip = $mainTip; mainMerged = $needsMain; branch = Current-Branch }
}
function New-Snapshot {
    $scopes = Normalize-Paths -Values $Path
    $selected = @((Pending-State).All | Where-Object { In-Scope -Candidate $_ -Scopes $scopes })
    if (-not $selected.Count) { throw "No pending files exist in the declared snapshot paths." }
    $root = Join-Path $script:LogRoot ("snapshot-" + (Get-Date -Format "yyyyMMdd-HHmmss-fff") + "-" + [guid]::NewGuid().ToString("N"))
    $files = Join-Path $root "files"; [void](New-Item -ItemType Directory -Force -Path $files)
    $entries = [Collections.Generic.List[object]]::new()
    foreach ($file in $selected) {
        $absolute = Join-Path $script:RepositoryRoot $file
        $exists = Test-Path -LiteralPath $absolute -PathType Leaf
        $tracked = (Invoke-Git -Arguments @("ls-files", "--error-unmatch", "--", $file) -AllowFailure).ExitCode -eq 0
        $hash = $null
        if ($exists) {
            $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $absolute).Hash
            $destination = Join-Path $files $file
            [void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination))
            Copy-Item -LiteralPath $absolute -Destination $destination
        }
        $entries.Add([ordered]@{ path = $file; exists = $exists; tracked = $tracked; sha256 = $hash })
    }
    $manifest = [ordered]@{ format = 1; repository = $script:RepositoryRoot; branch = Current-Branch;
        head = (Invoke-Git -Arguments @("rev-parse", "HEAD")).Text.Trim(); scopes = $scopes; entries = @($entries) }
    $manifestPath = Join-Path $root "manifest.json"
    $manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestPath -Encoding utf8
    [ordered]@{ operation = "Snapshot"; snapshot = $manifestPath; files = $entries.Count; head = $manifest.head }
}
function Compare-Snapshot {
    if (-not $SnapshotPath) { throw "CompareSnapshot requires -SnapshotPath." }
    $manifestPath = (Resolve-Path -LiteralPath $SnapshotPath).Path
    $manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
    if ([IO.Path]::GetFullPath([string]$manifest.repository) -ne [IO.Path]::GetFullPath($script:RepositoryRoot)) {
        throw "Snapshot belongs to another repository."
    }
    $differences = [Collections.Generic.List[object]]::new()
    $trackingChanges = [Collections.Generic.List[object]]::new()
    foreach ($entry in $manifest.entries) {
        $absolute = Join-Path $script:RepositoryRoot ([string]$entry.path)
        $exists = Test-Path -LiteralPath $absolute -PathType Leaf
        $tracked = (Invoke-Git -Arguments @("ls-files", "--error-unmatch", "--", [string]$entry.path) -AllowFailure).ExitCode -eq 0
        $hash = if ($exists) { (Get-FileHash -Algorithm SHA256 -LiteralPath $absolute).Hash } else { $null }
        if ($tracked -ne [bool]$entry.tracked) {
            $trackingChanges.Add([ordered]@{ path = $entry.path; before = [bool]$entry.tracked; after = $tracked })
        }
        if ($exists -ne [bool]$entry.exists -or $hash -ne $entry.sha256) {
            $differences.Add([ordered]@{ path = $entry.path; expectedExists = [bool]$entry.exists; actualExists = $exists;
                expectedSha256 = $entry.sha256; actualSha256 = $hash })
        }
    }
    $expected = @($manifest.entries | ForEach-Object { [string]$_.path })
    foreach ($extra in @((Pending-State).All | Where-Object { (In-Scope -Candidate $_ -Scopes @($manifest.scopes)) -and $_ -notin $expected })) {
        $differences.Add([ordered]@{ path = $extra; reason = "unexpected-path" })
    }
    [ordered]@{ operation = "CompareSnapshot"; snapshot = $manifestPath; match = -not [bool]$differences.Count;
        differences = @($differences); trackingChanges = @($trackingChanges) }
}
function Commit-Task {
    if (-not $ConfirmExecution) { throw "Commit requires -ConfirmExecution." }
    if ($Authorization -notin @("ExplicitUser", "TaskBoundary")) { throw "Commit requires an allowed -Authorization." }
    if (-not $ExpectedBranch -or -not $Message) { throw "Commit requires -ExpectedBranch and -Message." }
    Assert-StableGit
    if ((Current-Branch) -ne $ExpectedBranch) { throw "Expected '$ExpectedBranch', found '$(Current-Branch)'." }
    $scopes = Normalize-Paths -Values $Path
    $pending = Pending-State
    if ($pending.Staged.Count) { throw "Commit refuses pre-existing staged changes: $($pending.Staged -join ', ')." }
    $outside = @($pending.All | Where-Object { -not (In-Scope -Candidate $_ -Scopes $scopes) })
    if ($outside.Count) { throw "Pending paths outside declared task scope: $($outside -join ', ')." }
    $categories = @($scopes | ForEach-Object { Policy-Category -Candidate $_ } | Sort-Object -Unique)
    if ($categories -contains "Forbidden" -or $categories -contains "Unknown") { throw "Commit scope contains forbidden or unknown ownership." }
    if ($Authorization -eq "TaskBoundary" -and (($categories -contains "Mixed") -or $categories.Count -gt 1)) {
        throw "TaskBoundary cannot authorize mixed or cross-cutting work."
    }
    $validation = Run-Validation -PermitDirty
    $start = (Invoke-Git -Arguments @("rev-parse", "HEAD")).Text.Trim()
    [void](Invoke-Git -Arguments (@("add", "--all", "--") + $scopes))
    $staged = @((Invoke-Git -Arguments @("diff", "--cached", "--name-only")).Lines | Where-Object { $_ })
    if (-not $staged.Count) { throw "Declared paths produced no staged changes." }
    if (@($staged | Where-Object { -not (In-Scope -Candidate $_ -Scopes $scopes) }).Count) { throw "Staging escaped scope." }
    [void](Invoke-Git -Arguments @("commit", "-m", $Message))
    $head = (Invoke-Git -Arguments @("rev-parse", "HEAD")).Text.Trim()
    if ((Invoke-Git -Arguments @("rev-parse", "HEAD^1")).Text.Trim() -ne $start) { throw "Unexpected commit parent." }
    [ordered]@{ operation = "Commit"; branch = Current-Branch; commit = $head; parent = $start;
        authorization = $Authorization; paths = $staged; validation = $validation }
}
function Checked-OutBranches {
    @((Invoke-Git -Arguments @("worktree", "list", "--porcelain")).Lines |
        Where-Object { $_.StartsWith("branch refs/heads/") } | ForEach-Object { $_.Substring(18) })
}
function Merge-Main {
    if (-not $ConfirmExecution -or $Authorization -ne "ExplicitUser") { throw "MergeMain requires explicit authorization and -ConfirmExecution." }
    Assert-MainExists
    Assert-StableGit
    $mainBranch = [string]$script:Policy.MainBranch
    if ((Current-Branch) -ne $mainBranch) { throw "MergeMain must start on $mainBranch." }
    if ((Pending-State).All.Count) { throw "MergeMain requires a clean worktree." }
    $targets = @(Minecraft-Branches); if (-not $targets.Count) { throw "No local mc/* branches exist." }
    $blocked = @($targets | Where-Object { $_ -in @(Checked-OutBranches) })
    if ($blocked.Count) { throw "Target branches are checked out in other worktrees: $($blocked -join ', ')." }
    if (-not $FinalBranch) { $FinalBranch = $mainBranch }
    if (-not (Branch-Exists -Name $FinalBranch)) { throw "Final branch does not exist: $FinalBranch" }
    $main = (Invoke-Git -Arguments @("rev-parse", $mainBranch)).Text.Trim()
    $preflight = [Collections.Generic.List[object]]::new(); $skipped = [Collections.Generic.List[string]]::new()
    foreach ($target in $targets) {
        if ((Invoke-Git -Arguments @("merge-base", "--is-ancestor", $mainBranch, $target) -AllowFailure).ExitCode -eq 0) {
            $skipped.Add($target); continue
        }
        $temp = Join-Path ([IO.Path]::GetTempPath()) ("main-merge-preflight-" + [guid]::NewGuid().ToString("N"))
        [void](Invoke-Git -Arguments @("worktree", "add", "--detach", $temp, $target))
        try {
            $merge = Invoke-Git -Root $temp -Arguments @("merge", "--no-ff", "--no-commit", $mainBranch) -AllowFailure
            if ($merge.ExitCode -ne 0) { throw "Merge preflight failed for ${target}: $($merge.Text)" }
            $tree = (Invoke-Git -Root $temp -Arguments @("write-tree")).Text.Trim()
            $validation = Run-Validation -Root $temp -PermitDirty -SkipRepositoryPolicy -ProfileOverride "Minecraft"
            $targetTip = (Invoke-Git -Arguments @("rev-parse", $target)).Text.Trim()
            $preflight.Add([ordered]@{ branch = $target; targetTip = $targetTip; tree = $tree; validation = $validation })
        } finally {
            [void](Invoke-Git -Root $temp -Arguments @("merge", "--abort") -AllowFailure)
            [void](Invoke-Git -Arguments @("worktree", "remove", "--force", $temp) -AllowFailure)
        }
    }
    if ((Invoke-Git -Arguments @("rev-parse", $mainBranch)).Text.Trim() -ne $main) { throw "$mainBranch moved during merge preflight." }
    foreach ($item in $preflight) {
        if ((Invoke-Git -Arguments @("rev-parse", [string]$item.branch)).Text.Trim() -ne [string]$item.targetTip) {
            throw "Target branch moved during merge preflight: $($item.branch)"
        }
    }
    $merged = [Collections.Generic.List[object]]::new()
    foreach ($item in $preflight) {
        [void](Invoke-Git -Arguments @("switch", [string]$item.branch))
        [void](Invoke-Git -Arguments @("merge", "--no-ff", $main, "-m", "Merge branch 'main' into $($item.branch)"))
        $tree = (Invoke-Git -Arguments @("rev-parse", "HEAD^{tree}")).Text.Trim()
        if ($tree -ne $item.tree) { throw "Actual merge tree differs from preflight for $($item.branch)." }
        $merged.Add([ordered]@{ branch = $item.branch; commit = (Invoke-Git -Arguments @("rev-parse", "HEAD")).Text.Trim(); tree = $tree })
    }
    [void](Invoke-Git -Arguments @("switch", $FinalBranch))
    $policy = Assert-Policy
    [ordered]@{ operation = "MergeMain"; main = $main; merged = @($merged); skipped = @($skipped);
        finalBranch = Current-Branch; policy = $policy }
}
function Push-Refs {
    if (-not $ConfirmExecution -or $Authorization -ne "ExplicitUser") { throw "Push requires explicit authorization and -ConfirmExecution." }
    Assert-MainExists
    [void](Assert-Policy -PermitDirty)
    if (-not $Remote -or $Remote.StartsWith("-") -or $Remote -match '\s' -or -not $RefSpec -or
        $RefSpec -match '[*?\[\s]' -or $RefSpec.StartsWith(":") -or $RefSpec.EndsWith(":") -or
        $RefSpec.StartsWith("+") -or $RefSpec.StartsWith("-") -or $RefSpec.StartsWith("^")) {
        throw "Push requires one exact non-deletion -Remote and -RefSpec."
    }
    if ((Invoke-Git -Arguments @("remote", "get-url", $Remote) -AllowFailure).ExitCode -ne 0) { throw "Unknown remote '$Remote'." }
    $arguments = [Collections.Generic.List[string]]::new(); $arguments.Add("push")
    if ($ForceMode -ne "None") {
        if ($ForceAuthorization -ne "ExplicitForceUser") { throw "Force push requires ForceAuthorization=ExplicitForceUser." }
        $arguments.Add($(if ($ForceMode -eq "ForceWithLease") { "--force-with-lease" } else { "--force" }))
    }
    $arguments.Add($Remote); $arguments.Add($RefSpec)
    $result = Invoke-Git -Arguments @($arguments)
    [ordered]@{ operation = "Push"; remote = $Remote; refSpec = $RefSpec; forceMode = $ForceMode; output = $result.Text }
}

try {
    $result = switch ($Operation) {
        "Inspect" { Inspect-Result }
        "Classify" { Classify-Result }
        "PrepareMinecraftBranch" { Prepare-MinecraftBranch }
        "Validate" { $validation = Run-Validation -PermitDirty:$AllowDirty; [ordered]@{ operation = "Validate"; success = $true;
                branch = Current-Branch; validation = $validation } }
        "Snapshot" { New-Snapshot }
        "CompareSnapshot" { Compare-Snapshot }
        "Commit" { Commit-Task }
        "MergeMain" { Merge-Main }
        "Push" { Push-Refs }
        "Audit" { $policyResult = Assert-Policy -PermitDirty:$AllowDirty; [ordered]@{ operation = "Audit"; success = $true;
                inspect = Inspect-Result; policy = $policyResult } }
    }
    Write-Result -Value $result
} catch {
    $message = $_.Exception.Message
    if ($message.Length -gt 3000) { $message = $message.Substring(0, 3000) + "..." }
    Write-Result -Value ([ordered]@{ operation = $Operation; success = $false; error = $message;
        line = $_.InvocationInfo.ScriptLineNumber; stack = $_.ScriptStackTrace })
    exit 1
}
