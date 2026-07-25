[CmdletBinding()]
param(
    [string]$WorkflowScript = (Join-Path $PSScriptRoot "repository-workflow.ps1")
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$workflow = (Resolve-Path -LiteralPath $WorkflowScript).Path
$workflowDirectory = Split-Path -Parent $workflow
$sourceRoot = @(& git -C $workflowDirectory rev-parse --show-toplevel 2>&1)
if ($LASTEXITCODE -ne 0) { throw "Cannot locate the source repository: $($sourceRoot -join [Environment]::NewLine)" }
$sourceRoot = "$($sourceRoot[-1])"
$profileSource = Join-Path $sourceRoot ".agents/repository-profile.psd1"
$core = Import-PowerShellDataFile -LiteralPath (Join-Path $workflowDirectory "repository-policy.psd1")
$profile = Import-PowerShellDataFile -LiteralPath $profileSource
$policy = [ordered]@{}
foreach ($key in $core.Keys) { $policy[$key] = $core[$key] }
foreach ($key in $profile.Keys) { $policy[$key] = $profile[$key] }
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ("repository-workflow-tests-" + [guid]::NewGuid().ToString("N"))
$script:Passed = 0
$git = (Get-Command git -CommandType Application).Source
$pwsh = (Get-Process -Id $PID).Path

function Assert-True { param([bool]$Condition, [string]$Message) if (-not $Condition) { throw $Message }; $script:Passed++ }
function Git {
    param([string]$Root, [string[]]$Arguments, [switch]$AllowFailure)
    $output = @(& $git -C $Root @Arguments 2>&1 | ForEach-Object { "$_" }); $code = $LASTEXITCODE
    if ($code -ne 0 -and -not $AllowFailure) { throw "fixture git failed: $($Arguments -join ' '): $($output -join [Environment]::NewLine)" }
    [pscustomobject]@{ ExitCode = $code; Lines = $output; Text = $output -join [Environment]::NewLine }
}
function Set-File {
    param([string]$Root, [string]$Relative, [string]$Content)
    $path = Join-Path $Root $Relative; $parent = Split-Path -Parent $path
    if ($parent) { [void](New-Item -ItemType Directory -Force -Path $parent) }
    [IO.File]::WriteAllText($path, $Content, [Text.UTF8Encoding]::new($false))
}
function Install-RepositoryProfile {
    param([string]$Root)
    $destination = Join-Path $Root ".agents/repository-profile.psd1"
    [void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination))
    Copy-Item -LiteralPath $profileSource -Destination $destination
    Set-File $Root ([string]$policy.RepositoryVerifier) "param([string]`$RepoRoot,[switch]`$AllowContractVersionChange)`n'{`"success`":true}'`n"
}
function Invoke-Workflow {
    param([string]$Root, [string[]]$Arguments, [switch]$ExpectFailure)
    $output = @(& $pwsh -NoProfile -File $workflow -RepoRoot $Root @Arguments 2>&1 | ForEach-Object { "$_" }); $code = $LASTEXITCODE
    if (-not $ExpectFailure -and $code -ne 0) { throw "workflow failed: $($output -join [Environment]::NewLine)" }
    if ($ExpectFailure -and $code -eq 0) { throw "workflow unexpectedly succeeded: $($Arguments -join ' ')" }
    $json = $output[-1] | ConvertFrom-Json
    [pscustomobject]@{ ExitCode = $code; Json = $json; Output = $output }
}
function Assert-ProfileRejected {
    param([string]$Root, [string]$Content, [string]$Pattern)
    $path = Join-Path $Root ".agents/repository-profile.psd1"
    $bytes = [IO.File]::ReadAllBytes($path)
    try {
        [IO.File]::WriteAllText($path, $Content, [Text.UTF8Encoding]::new($false))
        $failure = Invoke-Workflow $Root @("-Operation", "Inspect") -ExpectFailure
        Assert-True ($failure.Json.error -match $Pattern) "Invalid profile was not rejected with '$Pattern'."
    } finally {
        [IO.File]::WriteAllBytes($path, $bytes)
    }
}

try {
    [void](New-Item -ItemType Directory -Force -Path $testRoot)
    $repo = Join-Path $testRoot "repo"
    & $git init -q -b main $repo
    [void](Git $repo @("config", "user.name", "Workflow Fixture"))
    [void](Git $repo @("config", "user.email", "fixture@example.invalid"))
    [void](Git $repo @("config", "core.autocrlf", "false"))
    Install-RepositoryProfile $repo

    $shared = [string]$policy.SharedPaths[0]
    $versionScope = [string]$policy.VersionPaths[0]
    $mixed = [string]$policy.MixedPaths[0]
    Set-File $repo $shared "shared`n"
    Set-File $repo $mixed "mixed`n"
    Set-File $repo "gradle.properties" "modVersion=1`nloaderVersion=1`nminecraftVersion=1.21.1`n"
    Set-File $repo "gradlew.bat" "@echo off`r`nexit /b 0`r`n"
    [void](Git $repo @("add", ".")); [void](Git $repo @("commit", "-q", "-m", "base"))
    [void](Git $repo @("branch", "port-base"))
    [void](Git $repo @("switch", "-q", "-c", "mc/1.21.1"))
    Set-File $repo (Join-Path $versionScope "value.txt") "one`n"
    [void](Git $repo @("add", ".")); [void](Git $repo @("commit", "-q", "-m", "version base"))
    [void](Git $repo @("switch", "-q", "main"))

    $inspect = Invoke-Workflow $repo @("-Operation", "Inspect")
    Assert-True ($inspect.Json.operation -eq "Inspect") "Inspect operation missing."
    Assert-True ($inspect.Json.branch -eq "main") "Inspect branch mismatch."
    Assert-True (-not $inspect.Json.dirty) "Fixture should start clean."
    Assert-True ($inspect.Json.mainExists) "main should exist."
    Assert-True (@($inspect.Json.minecraftBranches) -contains "mc/1.21.1") "Minecraft branch missing."
    Assert-True (@($inspect.Json.inProgress).Count -eq 0) "Unexpected in-progress state."

    $categories = @{}
    foreach ($candidate in @($shared, $versionScope, $mixed, "unknown.txt")) {
        $classification = Invoke-Workflow $repo @("-Operation", "Classify", "-Path", $candidate)
        $categories[$candidate] = $classification.Json.paths[0].category
    }
    Assert-True ($categories[$shared] -eq "Shared") "Shared classification failed."
    Assert-True ($categories[$versionScope] -eq "Minecraft") "Minecraft classification failed."
    Assert-True ($categories[$mixed] -eq "Mixed") "Mixed classification failed."
    Assert-True ($categories["unknown.txt"] -eq "Unknown") "Unknown classification failed."
    foreach ($candidate in @($policy.SharedPaths)) {
        $classification = Invoke-Workflow $repo @("-Operation", "Classify", "-Path", [string]$candidate)
        Assert-True ($classification.Json.paths[0].category -eq "Shared") "Shared profile path was misclassified: $candidate"
    }
    foreach ($candidate in @($policy.VersionPaths)) {
        $classification = Invoke-Workflow $repo @("-Operation", "Classify", "-Path", [string]$candidate)
        Assert-True ($classification.Json.paths[0].category -eq "Minecraft") "Minecraft profile path was misclassified: $candidate"
    }
    foreach ($candidate in @($policy.MixedPaths)) {
        $classification = Invoke-Workflow $repo @("-Operation", "Classify", "-Path", [string]$candidate)
        Assert-True ($classification.Json.paths[0].category -eq "Mixed") "Mixed profile path was misclassified: $candidate"
    }
    $mixedClassification = Invoke-Workflow $repo @("-Operation", "Classify", "-Path", $mixed)
    Assert-True ($mixedClassification.Json.requiresSemanticReview) "Mixed paths should require semantic review."

    $existing = Invoke-Workflow $repo @("-Operation", "PrepareMinecraftBranch", "-MinecraftVersion", "1.21.1")
    Assert-True ($existing.Json.exists) "Existing Minecraft branch was not detected."
    Assert-True (-not $existing.Json.switched) "Query must not switch branches."
    Assert-True ((Git $repo @("branch", "--show-current")).Text -eq "main") "Query changed the current branch."
    $missing = Invoke-Workflow $repo @("-Operation", "PrepareMinecraftBranch", "-MinecraftVersion", "1.22.0")
    Assert-True ($missing.Json.requiresBaseSelection) "Missing branch must request a base."
    Assert-True ((Git $repo @("show-ref", "--verify", "--quiet", "refs/heads/mc/1.22.0") -AllowFailure).ExitCode -ne 0) "Query created a branch."
    $planned = Invoke-Workflow $repo @("-Operation", "PrepareMinecraftBranch", "-MinecraftVersion", "1.22.0", "-BaseBranch", "port-base")
    Assert-True ($planned.Json.requiresConfirmation) "Base selection must still require execution confirmation."

    [void](Git $repo @("switch", "-q", "-c", "conflict-base"))
    Set-File $repo $mixed "base conflict`n"
    [void](Git $repo @("add", $mixed)); [void](Git $repo @("commit", "-q", "-m", "conflicting base"))
    [void](Git $repo @("switch", "-q", "main"))
    Set-File $repo $mixed "main conflict`n"
    Set-File $repo "main-marker.txt" "main advance`n"
    [void](Git $repo @("add", ".")); [void](Git $repo @("commit", "-q", "-m", "advance main"))
    $mainBeforeConflict = (Git $repo @("rev-parse", "main")).Text.Trim()
    $baseBeforeConflict = (Git $repo @("rev-parse", "conflict-base")).Text.Trim()
    $conflict = Invoke-Workflow $repo @("-Operation", "PrepareMinecraftBranch", "-MinecraftVersion", "1.23.0",
        "-BaseBranch", "conflict-base", "-Authorization", "ExplicitUser", "-ConfirmExecution") -ExpectFailure
    Assert-True ($conflict.Json.error -match "preflight failed") "Conflicting branch creation did not fail in preflight."
    Assert-True ((Git $repo @("rev-parse", "main")).Text.Trim() -eq $mainBeforeConflict) "Conflict moved main."
    Assert-True ((Git $repo @("rev-parse", "conflict-base")).Text.Trim() -eq $baseBeforeConflict) "Conflict moved its base."
    Assert-True ((Git $repo @("show-ref", "--verify", "--quiet", "refs/heads/mc/1.23.0") -AllowFailure).ExitCode -ne 0) "Conflict created the target ref."
    [void](Git $repo @("switch", "-q", "mc/1.21.1")); [void](Git $repo @("merge", "-q", "--no-ff", "main", "-m", "Merge main"))
    [void](Git $repo @("switch", "-q", "main"))
    $created = Invoke-Workflow $repo @("-Operation", "PrepareMinecraftBranch", "-MinecraftVersion", "1.22.0",
        "-BaseBranch", "port-base", "-Authorization", "ExplicitUser", "-ConfirmExecution")
    Assert-True ($created.Json.created) "Missing branch was not created."
    Assert-True ($created.Json.mainMerged) "Latest main was not merged into the new branch."
    Assert-True ($created.Json.branch -eq "mc/1.22.0") "New branch was not checked out."
    Assert-True ((Git $repo @("merge-base", "--is-ancestor", "main", "mc/1.22.0") -AllowFailure).ExitCode -eq 0) "main is not an ancestor."
    Assert-True ((Git $repo @("show", "mc/1.22.0:main-marker.txt") -AllowFailure).ExitCode -eq 0) "main content is missing."

    Set-File $repo (Join-Path $versionScope "value.txt") "pending`n"
    $snapshot = Invoke-Workflow $repo @("-Operation", "Snapshot", "-Path", $versionScope)
    Assert-True (Test-Path -LiteralPath $snapshot.Json.snapshot) "Snapshot manifest missing."
    Assert-True ($snapshot.Json.files -eq 1) "Snapshot file count mismatch."
    $same = Invoke-Workflow $repo @("-Operation", "CompareSnapshot", "-SnapshotPath", $snapshot.Json.snapshot)
    Assert-True ($same.Json.match) "Unchanged snapshot should match."
    Set-File $repo (Join-Path $versionScope "value.txt") "different`n"
    $different = Invoke-Workflow $repo @("-Operation", "CompareSnapshot", "-SnapshotPath", $snapshot.Json.snapshot)
    Assert-True (-not $different.Json.match) "Changed snapshot should differ."
    Assert-True (@($different.Json.differences).Count -eq 1) "Expected one snapshot difference."
    [void](Git $repo @("restore", "."))

    $newVersionFile = Join-Path $versionScope "new.txt"
    Set-File $repo $newVersionFile "new pending file`n"
    $newSnapshot = Invoke-Workflow $repo @("-Operation", "Snapshot", "-Path", $newVersionFile)
    [void](Git $repo @("add", "--", $newVersionFile)); [void](Git $repo @("commit", "-q", "-m", "track snapshot fixture"))
    $committedSnapshot = Invoke-Workflow $repo @("-Operation", "CompareSnapshot", "-SnapshotPath", $newSnapshot.Json.snapshot)
    Assert-True ($committedSnapshot.Json.match) "Commit-only tracking transition changed snapshot content."
    Assert-True (@($committedSnapshot.Json.trackingChanges).Count -eq 1) "Tracking transition was not reported."

    [void](Git $repo @("switch", "-q", "main"))
    Set-File $repo "gradle.properties" "modVersion=2`n"
    $guarded = Invoke-Workflow $repo @("-Operation", "Validate", "-AllowDirty", "-SkipBuild") -ExpectFailure
    Assert-True (-not $guarded.Json.success) "Product version guard should fail."
    Assert-True ($guarded.Json.error -match "contract version") "Guard failure reason missing: $($guarded.Json.error) line=$($guarded.Json.line) stack=$($guarded.Json.stack)"
    $authorized = Invoke-Workflow $repo @("-Operation", "Validate", "-AllowDirty", "-SkipBuild", "-ContractVersionAuthorized")
    Assert-True ($authorized.Json.success) "Authorized product version validation failed."
    [void](Git $repo @("restore", "gradle.properties"))

    Set-File $repo "gradle.properties" "modVersion=1`nloaderVersion=2`nminecraftVersion=1.21.1`n"
    $unexplainedDependency = Invoke-Workflow $repo @("-Operation", "Validate", "-AllowDirty", "-SkipBuild") -ExpectFailure
    Assert-True ($unexplainedDependency.Json.error -match "Dependency version") "Unexplained dependency update was not rejected."
    $explainedDependency = Invoke-Workflow $repo @("-Operation", "Validate", "-AllowDirty", "-SkipBuild",
        "-DependencyVersionReason", "Required by the fixture fix")
    Assert-True ($explainedDependency.Json.success) "Necessary dependency update was not accepted."
    Assert-True ($explainedDependency.Json.validation.policy.dependencyVersionReason -eq "Required by the fixture fix") "Dependency evidence was not recorded."
    [void](Git $repo @("restore", "gradle.properties"))

    Set-File $repo "gradle.properties" "modVersion=1`nloaderVersion=1`nminecraftVersion=1.22.0`n"
    $wrongMinecraftBranch = Invoke-Workflow $repo @("-Operation", "Validate", "-AllowDirty", "-SkipBuild") -ExpectFailure
    Assert-True ($wrongMinecraftBranch.Json.error -match "Minecraft version") "Minecraft version changed outside its branch."
    [void](Git $repo @("restore", "gradle.properties"))

    [void](Git $repo @("switch", "-q", "mc/1.22.0"))
    Set-File $repo "gradle.properties" "modVersion=1`nloaderVersion=1`nminecraftVersion=1.22.0`n"
    $matchingMinecraftBranch = Invoke-Workflow $repo @("-Operation", "Validate", "-AllowDirty", "-SkipBuild")
    Assert-True ($matchingMinecraftBranch.Json.success) "Matching Minecraft branch version was rejected."
    Assert-True (@($matchingMinecraftBranch.Json.validation.policy.contractVersionLines).Count -eq 0) "Minecraft version entered the product contract guard."
    [void](Git $repo @("restore", "gradle.properties"))
    Set-File $repo (Join-Path $versionScope "value.txt") "committed`n"
    Set-File $repo $shared "unrelated staged content`n"
    [void](Git $repo @("add", "--", $shared))
    $stagedDenied = Invoke-Workflow $repo @("-Operation", "Commit", "-Path", $versionScope, "-ExpectedBranch", "mc/1.22.0",
        "-Message", "Update version fixture", "-Authorization", "TaskBoundary", "-ConfirmExecution", "-SkipBuild") -ExpectFailure
    Assert-True ($stagedDenied.Json.error -match "pre-existing staged") "Pre-existing staging was not rejected."
    [void](Git $repo @("reset", "-q", "HEAD", "--", $shared)); [void](Git $repo @("restore", "--", $shared))
    $commit = Invoke-Workflow $repo @("-Operation", "Commit", "-Path", $versionScope, "-ExpectedBranch", "mc/1.22.0",
        "-Message", "Update version fixture", "-Authorization", "TaskBoundary", "-ConfirmExecution", "-SkipBuild")
    Assert-True ($commit.Json.operation -eq "Commit") "Commit operation missing."
    Assert-True ($commit.Json.authorization -eq "TaskBoundary") "Commit authorization mismatch."
    Assert-True (@($commit.Json.paths).Count -eq 1) "Exact-path commit count mismatch."
    Assert-True (-not (Git $repo @("status", "--porcelain=v1")).Lines.Count) "Commit left a dirty tree."
    Set-File $repo $mixed "mixed changed`n"
    $mixedCommit = Invoke-Workflow $repo @("-Operation", "Commit", "-Path", $mixed, "-ExpectedBranch", "mc/1.22.0",
        "-Message", "Change mixed", "-Authorization", "TaskBoundary", "-ConfirmExecution", "-SkipBuild") -ExpectFailure
    Assert-True ($mixedCommit.Json.error -match "mixed or cross-cutting") "Mixed TaskBoundary was not rejected."
    [void](Git $repo @("restore", $mixed))
    Set-File $repo "unknown.txt" "unknown ownership`n"
    $unknownCommit = Invoke-Workflow $repo @("-Operation", "Commit", "-Path", "unknown.txt", "-ExpectedBranch", "mc/1.22.0",
        "-Message", "Change unknown", "-Authorization", "ExplicitUser", "-ConfirmExecution", "-SkipBuild") -ExpectFailure
    Assert-True ($unknownCommit.Json.error -match "unknown ownership") "Unknown ownership was not rejected."
    Remove-Item -LiteralPath (Join-Path $repo "unknown.txt") -Force

    $bare = Join-Path $testRoot "remote.git"; & $git init -q --bare $bare
    [void](Git $repo @("remote", "add", "fixture", $bare))
    $noConfirm = Invoke-Workflow $repo @("-Operation", "Push", "-Remote", "fixture", "-RefSpec", "HEAD:refs/heads/test",
        "-Authorization", "ExplicitUser") -ExpectFailure
    Assert-True ($noConfirm.Json.error -match "ConfirmExecution") "Push confirmation gate failed."
    $push = Invoke-Workflow $repo @("-Operation", "Push", "-Remote", "fixture", "-RefSpec", "HEAD:refs/heads/test",
        "-Authorization", "ExplicitUser", "-ConfirmExecution")
    Assert-True ($push.Json.forceMode -eq "None") "Ordinary push force mode mismatch."
    Assert-True ((Git $bare @("show-ref", "--verify", "--quiet", "refs/heads/test") -AllowFailure).ExitCode -eq 0) "Exact ref was not pushed."
    $forceDenied = Invoke-Workflow $repo @("-Operation", "Push", "-Remote", "fixture", "-RefSpec", "HEAD:refs/heads/test",
        "-Authorization", "ExplicitUser", "-ConfirmExecution", "-ForceMode", "ForceWithLease") -ExpectFailure
    Assert-True ($forceDenied.Json.error -match "ExplicitForceUser") "Force permission gate failed."
    $forcePrefixDenied = Invoke-Workflow $repo @("-Operation", "Push", "-Remote", "fixture", "-RefSpec", "+HEAD:refs/heads/test",
        "-Authorization", "ExplicitUser", "-ConfirmExecution") -ExpectFailure
    Assert-True ($forcePrefixDenied.Json.error -match "exact non-deletion") "Force refspec prefix bypassed the force gate."
    $forceAllowed = Invoke-Workflow $repo @("-Operation", "Push", "-Remote", "fixture", "-RefSpec", "HEAD:refs/heads/test",
        "-Authorization", "ExplicitUser", "-ConfirmExecution", "-ForceMode", "ForceWithLease", "-ForceAuthorization", "ExplicitForceUser")
    Assert-True ($forceAllowed.Json.forceMode -eq "ForceWithLease") "Separately authorized force-with-lease was not accepted."

    [void](Git $repo @("switch", "-q", "main"))
    [void](Git $repo @("switch", "-q", "-c", "mc/conflict"))
    Set-File $repo ".agents/merge-fixture.txt" "conflicting Minecraft content`n"
    [void](Git $repo @("add", ".agents/merge-fixture.txt")); [void](Git $repo @("commit", "-q", "-m", "conflicting Minecraft merge fixture"))
    [void](Git $repo @("switch", "-q", "main"))
    Set-File $repo ".agents/merge-fixture.txt" "merge from main`n"
    [void](Git $repo @("add", ".agents/merge-fixture.txt")); [void](Git $repo @("commit", "-q", "-m", "shared merge fixture"))
    $tipsBeforeMergeConflict = @{}
    foreach ($ref in @("main", "mc/1.21.1", "mc/1.22.0", "mc/conflict")) {
        $tipsBeforeMergeConflict[$ref] = (Git $repo @("rev-parse", $ref)).Text.Trim()
    }
    $mergeConflict = Invoke-Workflow $repo @("-Operation", "MergeMain", "-Authorization", "ExplicitUser", "-ConfirmExecution",
        "-FinalBranch", "main", "-SkipBuild") -ExpectFailure
    Assert-True ($mergeConflict.Json.error -match "preflight failed") "MergeMain conflict was not caught in preflight."
    foreach ($ref in $tipsBeforeMergeConflict.Keys) {
        Assert-True ((Git $repo @("rev-parse", $ref)).Text.Trim() -eq $tipsBeforeMergeConflict[$ref]) "MergeMain conflict moved $ref."
    }
    Assert-True ((Git $repo @("branch", "--show-current")).Text.Trim() -eq "main") "MergeMain conflict changed the current branch."
    [void](Git $repo @("branch", "-D", "mc/conflict"))
    $merge = Invoke-Workflow $repo @("-Operation", "MergeMain", "-Authorization", "ExplicitUser", "-ConfirmExecution",
        "-FinalBranch", "mc/1.22.0", "-SkipBuild")
    Assert-True (@($merge.Json.merged).Count -eq 2) "MergeMain did not update every local Minecraft branch."
    Assert-True ($merge.Json.finalBranch -eq "mc/1.22.0") "MergeMain final branch mismatch."
    foreach ($target in @("mc/1.21.1", "mc/1.22.0")) {
        Assert-True ((Git $repo @("merge-base", "--is-ancestor", "main", $target) -AllowFailure).ExitCode -eq 0) "main was not merged into $target."
    }

    $audit = Invoke-Workflow $repo @("-Operation", "Audit")
    Assert-True ($audit.Json.success) "Final fixture audit failed."
    Assert-True ($audit.Json.policy.mainExists) "Audit did not enforce the required main branch."
    Assert-True (@($audit.Json.policy.minecraftBranches).Count -eq 2) "Audit Minecraft branch count mismatch."
    Assert-True (@($audit.Json.policy.contractVersionLines).Count -eq 0) "Unexpected guarded version lines."

    $profilePath = Join-Path $repo ".agents/repository-profile.psd1"
    $profileText = [IO.File]::ReadAllText($profilePath)
    Assert-ProfileRejected $repo ([regex]::Replace($profileText,
        '(?m)^\s*RepositoryVerifier\s*=.*\r?\n', "")) "missing required keys"
    Assert-ProfileRejected $repo ([regex]::Replace($profileText, '\}\s*$',
        "    UnexpectedKey = `"value`"`n}`n")) "unknown keys"
    Assert-ProfileRejected $repo ($profileText.Replace(
        "        `"$([string]$profile.ForbiddenTrackedPatterns[0])`"",
        "        `"[`"")) "Invalid ForbiddenTrackedPatterns regex"
    Assert-ProfileRejected $repo ($profileText.Replace(
        "        `"$shared`"", "        `"C:/absolute`"")) "unsafe repository-relative path"
    Assert-ProfileRejected $repo ($profileText.Replace(
        "        `"$mixed`"", "        `"$shared`"")) "appears in both"

    $noMain = Join-Path $testRoot "no-main"
    & $git init -q -b mc/1.21.1 $noMain
    [void](Git $noMain @("config", "user.name", "Workflow Fixture"))
    [void](Git $noMain @("config", "user.email", "fixture@example.invalid"))
    [void](Git $noMain @("config", "core.autocrlf", "false"))
    Install-RepositoryProfile $noMain
    Set-File $noMain $shared "shared`n"
    Set-File $noMain $mixed "mixed`n"
    Set-File $noMain "gradle.properties" "modVersion=1`nloaderVersion=1`nminecraftVersion=1.21.1`n"
    Set-File $noMain "gradlew.bat" "@echo off`r`nexit /b 0`r`n"
    Set-File $noMain (Join-Path $versionScope "value.txt") "base`n"
    [void](Git $noMain @("add", "."))
    [void](Git $noMain @("commit", "-q", "-m", "no-main base"))

    $noMainInspect = Invoke-Workflow $noMain @("-Operation", "Inspect")
    Assert-True (-not $noMainInspect.Json.mainExists) "Inspect should report the missing main branch."
    $refsBefore = (Git $noMain @("for-each-ref", "--format=%(refname)%09%(objectname)", "refs/heads")).Text
    Set-File $noMain (Join-Path $versionScope "value.txt") "pending`n"
    $noMainOperations = @(
        @("-Operation", "Validate", "-AllowDirty", "-SkipBuild"),
        @("-Operation", "Audit", "-AllowDirty"),
        @("-Operation", "PrepareMinecraftBranch", "-MinecraftVersion", "1.22.0"),
        @("-Operation", "Commit", "-Path", $versionScope, "-ExpectedBranch", "mc/1.21.1",
            "-Message", "Must not commit", "-Authorization", "ExplicitUser", "-ConfirmExecution", "-SkipBuild"),
        @("-Operation", "MergeMain", "-Authorization", "ExplicitUser", "-ConfirmExecution", "-FinalBranch", "mc/1.21.1", "-SkipBuild"),
        @("-Operation", "Push", "-Remote", "missing", "-RefSpec", "HEAD:refs/heads/test",
            "-Authorization", "ExplicitUser", "-ConfirmExecution")
    )
    foreach ($arguments in $noMainOperations) {
        $failure = Invoke-Workflow $noMain $arguments -ExpectFailure
        Assert-True ($failure.Json.error -eq "Required branch 'main' is missing.") "Missing-main failure was not fail-closed."
    }
    $refsAfter = (Git $noMain @("for-each-ref", "--format=%(refname)%09%(objectname)", "refs/heads")).Text
    Assert-True ($refsAfter -eq $refsBefore) "A missing-main operation changed refs."
    Assert-True (-not (Git $noMain @("diff", "--cached", "--name-only")).Lines.Count) "A missing-main operation changed staging."
    Assert-True ((Git $noMain @("branch", "--show-current")).Text.Trim() -eq "mc/1.21.1") "A missing-main operation changed branches."

    [ordered]@{ operation = "TestRepositoryWorkflow"; passed = $script:Passed; status = "PASS" } | ConvertTo-Json -Compress
} finally {
    if (Test-Path -LiteralPath $testRoot) { Remove-Item -LiteralPath $testRoot -Recurse -Force -ErrorAction SilentlyContinue }
}
