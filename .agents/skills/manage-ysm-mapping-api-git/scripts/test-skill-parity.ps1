[CmdletBinding()]
param(
    [string]$VerifierScript = (Join-Path $PSScriptRoot "verify-skill-parity.ps1")
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$verifier = (Resolve-Path -LiteralPath $VerifierScript).Path
$current = @(& git -C $PSScriptRoot rev-parse --show-toplevel 2>&1)
if ($LASTEXITCODE -ne 0) { throw "Cannot locate source repositories: $($current -join [Environment]::NewLine)" }
$current = "$($current[-1])"
$parent = Split-Path -Parent $current
$serverlessSource = if ((Split-Path -Leaf $current) -eq "Serverless-YSM") { $current } else { Join-Path $parent "Serverless-YSM" }
$mappingSource = if ((Split-Path -Leaf $current) -eq "YSM-Mapping-API") { $current } else { Join-Path $parent "YSM-Mapping-API" }
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ("skill-parity-tests-" + [guid]::NewGuid().ToString("N"))
$pwsh = (Get-Process -Id $PID).Path
$script:Passed = 0

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
    $script:Passed++
}
function Invoke-Parity {
    param([string]$Serverless, [string]$Mapping, [switch]$ExpectFailure)
    $output = @(& $pwsh -NoProfile -File $verifier -ServerlessRoot $Serverless -MappingRoot $Mapping 2>&1 |
        ForEach-Object { "$_" })
    $code = $LASTEXITCODE
    if (-not $ExpectFailure -and $code -ne 0) { throw "Parity verifier failed: $($output -join [Environment]::NewLine)" }
    if ($ExpectFailure -and $code -eq 0) { throw "Parity verifier unexpectedly succeeded." }
    [pscustomobject]@{ ExitCode = $code; Json = $output[-1] | ConvertFrom-Json }
}
function Assert-DetectedFailure {
    param([string]$Serverless, [string]$Mapping, [string]$Pattern)
    $result = Invoke-Parity $Serverless $Mapping -ExpectFailure
    Assert-True (-not $result.Json.success -and $result.Json.error -match $Pattern) "Expected parity failure '$Pattern' was not reported."
}
function Mutate-And-Restore {
    param([string]$Path, [scriptblock]$Mutation, [scriptblock]$Assertion)
    $bytes = [IO.File]::ReadAllBytes($Path)
    try { & $Mutation $Path; & $Assertion } finally { [IO.File]::WriteAllBytes($Path, $bytes) }
}

try {
    $actual = Invoke-Parity $serverlessSource $mappingSource
    Assert-True ($actual.Json.success) "Actual repository parity failed."
    Assert-True (@($actual.Json.excluded).Count -eq 2) "Excluded repository inputs were not reported."

    $serverless = Join-Path $testRoot "Serverless-YSM"
    $mapping = Join-Path $testRoot "YSM-Mapping-API"
    [void](New-Item -ItemType Directory -Force -Path $serverless, $mapping)
    Copy-Item -LiteralPath (Join-Path $serverlessSource ".agents") -Destination $serverless -Recurse
    Copy-Item -LiteralPath (Join-Path $mappingSource ".agents") -Destination $mapping -Recurse
    Assert-True ((Invoke-Parity $serverless $mapping).Json.success) "Copied fixture parity failed."

    $taskReference = Join-Path $mapping ".agents/skills/manage-ysm-mapping-api-git/references/task-boundaries.md"
    Mutate-And-Restore $taskReference { param($path) [IO.File]::AppendAllText($path, "`nMapping-only drift`n") } {
        Assert-DetectedFailure $serverless $mapping "Content parity mismatch"
    }
    Mutate-And-Restore $taskReference {
        param($path)
        $text = [IO.File]::ReadAllText($path).Replace("`r`n", "`n").Replace("`r", "`n")
        [IO.File]::WriteAllText($path, $text.Replace("`n", "`r`n"), [Text.UTF8Encoding]::new($false))
    } {
        Assert-True ((Invoke-Parity $serverless $mapping).Json.success) "Line-ending-only drift affected parity."
    }
    $workflow = Join-Path $mapping ".agents/skills/manage-ysm-mapping-api-git/scripts/repository-workflow.ps1"
    Mutate-And-Restore $workflow { param($path) [IO.File]::AppendAllText($path, "`n# script drift`n") } {
        Assert-DetectedFailure $serverless $mapping "Content parity mismatch"
    }
    $historyPolicy = Join-Path $mapping ".agents/skills/rewrite-ysm-mapping-api-history/references/rewrite-policy.md"
    Mutate-And-Restore $historyPolicy { param($path) Remove-Item -LiteralPath $path -Force } {
        Assert-DetectedFailure $serverless $mapping "Required parity file is missing"
    }

    [IO.File]::AppendAllText((Join-Path $mapping ".agents/repository-profile.psd1"), "`n# excluded profile drift`n")
    [IO.File]::AppendAllText((Join-Path $mapping ".agents/skills/manage-ysm-mapping-api-git/references/branch-ownership.md"),
        "`nExcluded ownership drift`n")
    Assert-True ((Invoke-Parity $serverless $mapping).Json.success) "Excluded repository inputs affected parity."

    [ordered]@{ operation = "TestSkillParity"; passed = $script:Passed; status = "PASS" } |
        ConvertTo-Json -Compress
} finally {
    if (Test-Path -LiteralPath $testRoot) {
        $resolved = [IO.Path]::GetFullPath($testRoot)
        $temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
        if ($resolved.StartsWith($temp, [StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}
