[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet("Inventory", "RegistryReport", "Audit")]
    [string]$Operation,
    [string]$RepoRoot = "",
    [string]$ProfilePath = "",
    [string]$CatalogPath = "",
    [string]$FixtureDirectory = "local-ysm",
    [string]$ReportPath = "build/reports/ysm-registry-report.json"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$script:GitExecutable = (Get-Command git -CommandType Application).Source
if (-not $RepoRoot) {
    $value = @(& git rev-parse --show-toplevel 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "Not inside a Git worktree." }
    $RepoRoot = "$($value[-1])"
}
$script:Root = (Resolve-Path -LiteralPath $RepoRoot).Path
function Git {
    param([string[]]$Arguments, [switch]$AllowFailure)
    $lines = @(& $script:GitExecutable -C $script:Root @Arguments 2>&1 | ForEach-Object { "$_" }); $code = $LASTEXITCODE
    if ($code -ne 0 -and -not $AllowFailure) { throw "git $($Arguments -join ' ') failed: $($lines -join [Environment]::NewLine)" }
    [pscustomobject]@{ ExitCode = $code; Lines = $lines; Text = $lines -join [Environment]::NewLine }
}
function Result { param($Value) $Value | ConvertTo-Json -Depth 10 -Compress }
function Resolve-InputFile {
    param([string]$Value, [string]$Label)
    if (-not $Value) { throw "-$Label is required." }
    $candidate = if ([IO.Path]::IsPathRooted($Value)) { $Value } else { Join-Path $script:Root $Value }
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) { throw "$Label does not exist: $Value" }
    (Resolve-Path -LiteralPath $candidate).Path
}
function Load-FixtureConfiguration {
    $profileFile = Resolve-InputFile $ProfilePath "ProfilePath"
    $catalogFile = Resolve-InputFile $CatalogPath "CatalogPath"
    $profile = Get-Content -Raw -LiteralPath $profileFile | ConvertFrom-Json
    $catalog = Get-Content -Raw -LiteralPath $catalogFile | ConvertFrom-Json
    if ($profile.formatVersion -ne 1 -or $catalog.formatVersion -ne 1) {
        throw "Profile and catalog must use formatVersion 1."
    }
    if (-not $profile.minecraftVersion -or $catalog.minecraftVersion -ne $profile.minecraftVersion) {
        throw "Profile and catalog Minecraft versions do not match."
    }
    $fixtures = @($catalog.fixtures)
    if (-not $fixtures.Count) { throw "Fixture catalog is empty." }
    $names = [Collections.Generic.List[string]]::new()
    foreach ($fixture in $fixtures) {
        $name = [string]$fixture.fileName
        if (-not $name -or [IO.Path]::GetFileName($name) -ne $name -or -not $name.EndsWith(".jar") -or
            -not [string]$fixture.ysmVersion -or -not [string]$fixture.loader) {
            throw "Fixture catalog contains an invalid entry."
        }
        if ($names.Contains($name)) { throw "Fixture catalog contains duplicate fileName: $name" }
        $names.Add($name)
    }
    [ordered]@{ profilePath = $profileFile; catalogPath = $catalogFile;
        minecraftVersion = [string]$profile.minecraftVersion; fixtures = $fixtures;
        expected = @($names | Sort-Object) }
}
function Inventory-Fixtures {
    $configuration = Load-FixtureConfiguration
    $directory = Join-Path $script:Root $FixtureDirectory
    $expected = @($configuration.expected)
    $actual = if (Test-Path -LiteralPath $directory -PathType Container) {
        @(Get-ChildItem -LiteralPath $directory -File -Filter "*.jar" | ForEach-Object Name | Sort-Object)
    } else { @() }
    $missing = @($expected | Where-Object { $_ -notin $actual })
    $extra = @($actual | Where-Object { $_ -notin $expected })
    $relativeActual = @($actual | ForEach-Object { "$FixtureDirectory/$_" })
    $ignored = if ($relativeActual.Count) {
        @((Git -Arguments (@("check-ignore", "--") + $relativeActual) -AllowFailure).Lines)
    } else { @() }
    $trackedSet = @((Git -Arguments @("ls-files")).Lines)
    $notIgnored = @($relativeActual | Where-Object { $_ -notin $ignored })
    $tracked = @($relativeActual | Where-Object { $_ -in $trackedSet })
    [ordered]@{ operation = "Inventory"; directory = $directory; profile = $configuration.profilePath;
        catalog = $configuration.catalogPath; minecraftVersion = $configuration.minecraftVersion;
        expectedCount = $expected.Count; actualCount = $actual.Count;
        complete = (-not $missing.Count -and -not $extra.Count -and -not $notIgnored.Count -and -not $tracked.Count);
        missing = $missing; extra = $extra; notIgnored = $notIgnored; tracked = $tracked }
}
function Run-RegistryReport {
    $inventory = Inventory-Fixtures
    if (-not $inventory.complete) { throw "Fixture inventory is incomplete or unsafe: $(ConvertTo-Json $inventory -Compress)" }
    $report = Join-Path $script:Root $ReportPath
    [void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $report))
    $relativeReport = [IO.Path]::GetRelativePath($script:Root, $report).Replace("\", "/")
    if ((Git -Arguments @("check-ignore", "--quiet", "--", $relativeReport) -AllowFailure).ExitCode -ne 0) {
        throw "Report destination is not ignored: $relativeReport"
    }
    $wrapper = Join-Path $script:Root "gradlew.bat"
    $arguments = "registry-report `"$($inventory.profile)`" `"$($inventory.catalog)`" `"$($inventory.directory)`" `"$report`""
    Push-Location -LiteralPath $script:Root
    try { & $wrapper :mapping-tool:run "--args=$arguments" --offline --no-daemon --no-parallel --no-problems-report; $code = $LASTEXITCODE }
    finally { Pop-Location }
    if ($code -ne 0) { throw "registry-report failed with exit code $code; inspect the ignored report/log output locally." }
    $parsed = Get-Content -Raw -LiteralPath $report | ConvertFrom-Json
    [ordered]@{ operation = "RegistryReport"; success = ($parsed.status -eq "PASS"); status = $parsed.status;
        targetCount = @($parsed.targets).Count; mismatchCount = @($parsed.mismatches).Count; report = $report }
}
function Audit-PrivateArtifacts {
    $inventory = Inventory-Fixtures
    $forbidden = @((Git -Arguments @("ls-files")).Lines | Where-Object {
        $_ -match '(^|/)local-ysm/' -or $_ -match '(^|/)ysm-analysis/' -or
        $_ -match '(^|/)test-fixtures/private/' -or $_ -match '(^|/)build/reports/' -or
        $_ -match '(^|/)(?:decompile[d]?|private-reports?|runtime-names?|whole-jar-graphs?)/' -or
        $_ -match '(^|/)(?:registry-report|name-report|whole-jar-graph)(?:[-_.].*)?\.(?:json|txt|csv|tsv|dot|graphml)$' -or
        $_ -match '\.(dll|so|dylib)$' -or ($_.EndsWith('.jar') -and $_ -ne 'gradle/wrapper/gradle-wrapper.jar')
    })
    $jarViolations = [Collections.Generic.List[object]]::new()
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    foreach ($jar in @(Get-ChildItem -LiteralPath $script:Root -Recurse -File -Filter "*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match '[\\/]build[\\/]libs[\\/]' })) {
        $archive = [IO.Compression.ZipFile]::OpenRead($jar.FullName)
        try {
            $bad = @($archive.Entries | Where-Object {
                $_.FullName.StartsWith("com/elfmcys/yesstevemodel/") -or
                $_.FullName.StartsWith("ysm_mapping_api/reference/") -or
                $_.FullName -match '(^|/)(?:local-ysm|ysm-analysis|private-reports?|decompile[d]?|runtime-names?|whole-jar-graphs?)/' -or
                $_.FullName -match '(^|/)(?:registry-report|name-report|whole-jar-graph)(?:[-_.].*)?\.(?:json|txt|csv|tsv|dot|graphml)$' -or
                $_.FullName -match '\.(?:dll|so|dylib)$'
            } | Select-Object -First 10 -ExpandProperty FullName)
            if ($bad.Count) { $jarViolations.Add([ordered]@{ jar = $jar.FullName; entries = $bad }) }
        } finally { $archive.Dispose() }
    }
    if ($forbidden.Count -or $jarViolations.Count -or $inventory.notIgnored.Count -or $inventory.tracked.Count) {
        throw "Private artifact audit failed: tracked=$($forbidden -join ', '); jarViolations=$($jarViolations.Count)."
    }
    [ordered]@{ operation = "Audit"; success = $true; inventoryComplete = $inventory.complete;
        trackedViolations = 0; distributionViolations = 0 }
}

try {
    $value = switch ($Operation) {
        "Inventory" { Inventory-Fixtures }
        "RegistryReport" { Run-RegistryReport }
        "Audit" { Audit-PrivateArtifacts }
    }
    Result -Value $value
} catch {
    Result -Value ([ordered]@{ operation = $Operation; success = $false; error = $_.Exception.Message })
    exit 1
}
