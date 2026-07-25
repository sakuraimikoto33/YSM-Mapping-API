[CmdletBinding()]
param(
    [string]$RepoRoot = "",
    [switch]$AllowContractVersionChange
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
if (-not $RepoRoot) {
    $value = @(& git rev-parse --show-toplevel 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "Not inside a Git worktree." }
    $RepoRoot = "$($value[-1])"
}
$root = (Resolve-Path -LiteralPath $RepoRoot).Path
function Require-Pattern {
    param([string]$RelativePath, [string]$Pattern, [string]$Label)
    $path = Join-Path $root $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing contract file: $RelativePath" }
    if ((Get-Content -Raw -LiteralPath $path) -notmatch $Pattern) { throw "$Label is not at the expected unreleased v1 value in $RelativePath." }
}
function Has-File {
    param([string]$RelativePath)
    Test-Path -LiteralPath (Join-Path $root $RelativePath) -PathType Leaf
}
$branch = "$(& git -C $root branch --show-current 2>$null)".Trim()
$minecraftBranch = $branch -match '^mc/'
$coreTarget = "api-core/src/main/java/net/okitsu/ysmmapping/api/MappingTarget.java"
$legacyTarget = "api/src/main/java/net/okitsu/ysmmapping/api/MappingTarget.java"
if (-not (Has-File $coreTarget) -and -not (Has-File $legacyTarget)) {
    throw "Missing shared mapping target contract; expected api-core or the pre-split api layout."
}
if ($minecraftBranch -and -not (Has-File "api/src/main/java/net/okitsu/ysmmapping/api/YsmSymbolKey.java")) {
    throw "Minecraft branches must contain the version-owned api contract."
}
if ($minecraftBranch -and -not (Has-File "common/src/main/java/net/okitsu/ysmmapping/internal/cache/MappingsDocument.java")) {
    throw "Minecraft branches must contain persisted common mapping contracts."
}
if (-not $AllowContractVersionChange) {
    if (Has-File "api/src/main/java/net/okitsu/ysmmapping/api/YsmSymbolKey.java") {
        Require-Pattern "api/src/main/java/net/okitsu/ysmmapping/api/YsmSymbolKey.java" 'definitionRevision\s*!=\s*1' "Definition revision"
    }
    if (Has-File "common/src/main/java/net/okitsu/ysmmapping/internal/bootstrap/RequestManifest.java") {
        Require-Pattern "common/src/main/java/net/okitsu/ysmmapping/internal/bootstrap/RequestManifest.java" 'schemaVersion\s*!=\s*1' "Request manifest schema"
        Require-Pattern "common/src/main/java/net/okitsu/ysmmapping/internal/cache/MappingSettings.java" 'schemaVersion\s*!=\s*1' "Settings schema"
        Require-Pattern "common/src/main/java/net/okitsu/ysmmapping/internal/cache/MappingsDocument.java" 'SCHEMA_VERSION\s*=\s*1' "Mappings schema"
        Require-Pattern "common/src/main/java/net/okitsu/ysmmapping/internal/cache/MappingsDocument.java" 'FINGERPRINT_ALGORITHM\s*=\s*1' "Fingerprint algorithm"
    }
}
$laterRequests = @(Get-ChildItem -LiteralPath $root -Recurse -File -Filter "requests-v*.json" -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match '^requests-v([2-9]|[1-9][0-9]+)\.json$' -and $_.FullName -notmatch '[\\/]build[\\/]' })
if (-not $AllowContractVersionChange -and $laterRequests.Count) {
    throw "Unexpected later request manifests: $($laterRequests.FullName -join ', ')"
}
$tracked = @(& git -C $root ls-files)
if ($LASTEXITCODE -ne 0) { throw "Unable to inspect tracked files." }
$forbidden = @($tracked | Where-Object {
    $_ -match '(^|/)local-ysm/' -or $_ -match '(^|/)ysm-analysis/' -or
    $_ -match '(^|/)test-fixtures/private/' -or $_ -match '(^|/)build/reports/' -or
    $_ -match '(^|/)(?:decompile[d]?|private-reports?|runtime-names?|whole-jar-graphs?)/' -or
    $_ -match '(^|/)(?:registry-report|name-report|whole-jar-graph)(?:[-_.].*)?\.(?:json|txt|csv|tsv|dot|graphml)$' -or
    $_ -match '\.(dll|so|dylib)$' -or ($_.EndsWith('.jar') -and $_ -ne 'gradle/wrapper/gradle-wrapper.jar')
})
if ($forbidden.Count) { throw "Private or proprietary tracked paths: $($forbidden -join ', ')" }
[ordered]@{ success = $true; branch = $branch; apiCore = (Has-File $coreTarget);
    minecraftApi = (Has-File "api/src/main/java/net/okitsu/ysmmapping/api/YsmSymbolKey.java");
    persistedCommon = (Has-File "common/src/main/java/net/okitsu/ysmmapping/internal/cache/MappingsDocument.java");
    requestSchema = 1; cacheSchema = 1; fingerprintAlgorithm = 1; definitionRevision = 1;
    authorizedVersionChange = [bool]$AllowContractVersionChange } | ConvertTo-Json -Compress
