[CmdletBinding()]
param(
    [string]$ServerlessRoot = "",
    [string]$MappingRoot = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-Roots {
    if ($ServerlessRoot -and $MappingRoot) {
        return [ordered]@{
            Serverless = (Resolve-Path -LiteralPath $ServerlessRoot).Path
            Mapping = (Resolve-Path -LiteralPath $MappingRoot).Path
        }
    }
    if ($ServerlessRoot -or $MappingRoot) { throw "Specify both -ServerlessRoot and -MappingRoot, or neither." }
    $current = @(& git -C $PSScriptRoot rev-parse --show-toplevel 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "Cannot locate the current repository: $($current -join [Environment]::NewLine)" }
    $current = "$($current[-1])"
    $parent = Split-Path -Parent $current
    $leaf = Split-Path -Leaf $current
    if ($leaf -eq "Serverless-YSM") {
        $serverless = $current
        $mapping = Join-Path $parent "YSM-Mapping-API"
    } elseif ($leaf -eq "YSM-Mapping-API") {
        $serverless = Join-Path $parent "Serverless-YSM"
        $mapping = $current
    } else {
        throw "Run from Serverless-YSM or YSM-Mapping-API, or specify both repository roots."
    }
    [ordered]@{
        Serverless = (Resolve-Path -LiteralPath $serverless).Path
        Mapping = (Resolve-Path -LiteralPath $mapping).Path
    }
}
function Resolve-RequiredFile {
    param([string]$Root, [string]$Relative)
    $path = Join-Path $Root $Relative
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required parity file is missing: $path" }
    (Resolve-Path -LiteralPath $path).Path
}
function Normalize-InstructionText {
    param([string]$Path)
    $text = [IO.File]::ReadAllText($Path).Replace("`r`n", "`n").Replace("`r", "`n")
    foreach ($pair in @(
        @("manage-serverless-ysm-git", "<git-skill>"),
        @("manage-ysm-mapping-api-git", "<git-skill>"),
        @("rewrite-serverless-ysm-history", "<history-skill>"),
        @("rewrite-ysm-mapping-api-history", "<history-skill>"),
        @("Serverless-YSM", "<repository>"),
        @("YSM-Mapping-API", "<repository>"),
        @("Serverless YSM", "<repository>"),
        @("YSM Mapping API", "<repository>")
    )) { $text = $text.Replace($pair[0], $pair[1]) }
    $text
}
function Normalize-ContentText {
    param([string]$Path)
    [IO.File]::ReadAllText($Path).Replace("`r`n", "`n").Replace("`r", "`n")
}

try {
    $roots = Resolve-Roots
    $exactPairs = @(
        @(".agents/skills/manage-serverless-ysm-git/scripts/repository-policy.psd1",
            ".agents/skills/manage-ysm-mapping-api-git/scripts/repository-policy.psd1"),
        @(".agents/skills/manage-serverless-ysm-git/references/task-boundaries.md",
            ".agents/skills/manage-ysm-mapping-api-git/references/task-boundaries.md"),
        @(".agents/skills/manage-serverless-ysm-git/scripts/repository-workflow.ps1",
            ".agents/skills/manage-ysm-mapping-api-git/scripts/repository-workflow.ps1"),
        @(".agents/skills/manage-serverless-ysm-git/scripts/test-repository-workflow.ps1",
            ".agents/skills/manage-ysm-mapping-api-git/scripts/test-repository-workflow.ps1"),
        @(".agents/skills/manage-serverless-ysm-git/scripts/verify-skill-parity.ps1",
            ".agents/skills/manage-ysm-mapping-api-git/scripts/verify-skill-parity.ps1"),
        @(".agents/skills/manage-serverless-ysm-git/scripts/test-skill-parity.ps1",
            ".agents/skills/manage-ysm-mapping-api-git/scripts/test-skill-parity.ps1"),
        @(".agents/skills/rewrite-serverless-ysm-history/references/rewrite-policy.md",
            ".agents/skills/rewrite-ysm-mapping-api-history/references/rewrite-policy.md"),
        @(".agents/skills/rewrite-serverless-ysm-history/scripts/history-safety.ps1",
            ".agents/skills/rewrite-ysm-mapping-api-history/scripts/history-safety.ps1"),
        @(".agents/skills/rewrite-serverless-ysm-history/scripts/test-history-safety.ps1",
            ".agents/skills/rewrite-ysm-mapping-api-history/scripts/test-history-safety.ps1")
    )
    $normalizedPairs = @(
        @(".agents/skills/manage-serverless-ysm-git/SKILL.md",
            ".agents/skills/manage-ysm-mapping-api-git/SKILL.md"),
        @(".agents/skills/manage-serverless-ysm-git/agents/openai.yaml",
            ".agents/skills/manage-ysm-mapping-api-git/agents/openai.yaml"),
        @(".agents/skills/rewrite-serverless-ysm-history/SKILL.md",
            ".agents/skills/rewrite-ysm-mapping-api-history/SKILL.md"),
        @(".agents/skills/rewrite-serverless-ysm-history/agents/openai.yaml",
            ".agents/skills/rewrite-ysm-mapping-api-history/agents/openai.yaml")
    )
    $exactResults = [Collections.Generic.List[object]]::new()
    foreach ($pair in $exactPairs) {
        $left = Resolve-RequiredFile $roots.Serverless $pair[0]
        $right = Resolve-RequiredFile $roots.Mapping $pair[1]
        $leftText = Normalize-ContentText $left
        $rightText = Normalize-ContentText $right
        if ($leftText -cne $rightText) { throw "Content parity mismatch: $($pair[0]) <> $($pair[1])." }
        $bytes = [Text.Encoding]::UTF8.GetBytes($leftText)
        $hash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes))
        $exactResults.Add([ordered]@{ serverless = $pair[0]; mapping = $pair[1]; normalizedSha256 = $hash })
    }
    $normalizedResults = [Collections.Generic.List[object]]::new()
    foreach ($pair in $normalizedPairs) {
        $left = Resolve-RequiredFile $roots.Serverless $pair[0]
        $right = Resolve-RequiredFile $roots.Mapping $pair[1]
        $leftText = Normalize-InstructionText $left
        $rightText = Normalize-InstructionText $right
        if ($leftText -cne $rightText) { throw "Normalized instruction mismatch: $($pair[0]) <> $($pair[1])." }
        $bytes = [Text.Encoding]::UTF8.GetBytes($leftText)
        $hash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes))
        $normalizedResults.Add([ordered]@{ serverless = $pair[0]; mapping = $pair[1]; normalizedSha256 = $hash })
    }
    [ordered]@{
        operation = "VerifySkillParity"
        success = $true
        exact = @($exactResults)
        normalized = @($normalizedResults)
        excluded = @(
            [ordered]@{ reason = "repository profile"; serverless = ".agents/repository-profile.psd1";
                mapping = ".agents/repository-profile.psd1" },
            [ordered]@{ reason = "branch ownership"; serverless = ".agents/skills/manage-serverless-ysm-git/references/branch-ownership.md";
                mapping = ".agents/skills/manage-ysm-mapping-api-git/references/branch-ownership.md" }
        )
    } | ConvertTo-Json -Depth 8 -Compress
} catch {
    [ordered]@{ operation = "VerifySkillParity"; success = $false; error = $_.Exception.Message } |
        ConvertTo-Json -Compress
    exit 1
}
