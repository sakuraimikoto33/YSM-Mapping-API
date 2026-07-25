[CmdletBinding()]
param([string]$FixtureScript = (Join-Path $PSScriptRoot "private-fixture-workflow.ps1"))

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$workflow = (Resolve-Path -LiteralPath $FixtureScript).Path
$pwsh = (Get-Process -Id $PID).Path
$git = (Get-Command git -CommandType Application).Source
$root = Join-Path ([IO.Path]::GetTempPath()) ("private-fixture-tests-" + [guid]::NewGuid().ToString("N"))
$profilePath = "fixtures/profile.json"
$catalogPath = "fixtures/catalog.json"
$script:Passed = 0
function Assert-True { param([bool]$Condition, [string]$Message) if (-not $Condition) { throw $Message }; $script:Passed++ }
function Git {
    param([string[]]$Arguments)
    $output = @(& $git -C $root @Arguments 2>&1 | ForEach-Object { "$_" }); $code = $LASTEXITCODE
    if ($code -ne 0) { throw "fixture git failed: $($Arguments -join ' '): $($output -join [Environment]::NewLine)" }
}
function Invoke-Fixture {
    param([string]$Operation, [switch]$ExpectFailure)
    $output = @(& $pwsh -NoProfile -File $workflow -RepoRoot $root -Operation $Operation `
        -ProfilePath $profilePath -CatalogPath $catalogPath 2>&1 | ForEach-Object { "$_" }); $code = $LASTEXITCODE
    if (-not $ExpectFailure -and $code -ne 0) { throw "fixture workflow failed: $($output -join [Environment]::NewLine)" }
    if ($ExpectFailure -and $code -eq 0) { throw "fixture workflow unexpectedly succeeded: $Operation" }
    $output[-1] | ConvertFrom-Json
}
function New-LeakingJar {
    param([string[]]$Entry)
    $jar = Join-Path $root "build/libs/leak.jar"
    [void](New-Item -ItemType Directory -Force -Path (Split-Path -Parent $jar))
    $stream = [IO.File]::Open($jar, [IO.FileMode]::Create)
    $archive = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Create)
    try { foreach ($name in $Entry) { $archive.CreateEntry($name).Open().Dispose() } }
    finally { $archive.Dispose(); $stream.Dispose() }
    $jar
}

try {
    [void](New-Item -ItemType Directory -Force -Path (Join-Path $root "local-ysm"))
    [void](New-Item -ItemType Directory -Force -Path (Join-Path $root "fixtures"))
    [IO.File]::WriteAllText((Join-Path $root ".gitignore"), "local-ysm/`nbuild/`n", [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $root $profilePath), @'
{"formatVersion":1,"minecraftVersion":"9.9.9","loaders":["fabric","other"],"symbols":[]}
'@, [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $root $catalogPath), @'
{"formatVersion":1,"minecraftVersion":"9.9.9","fixtures":[
{"fileName":"atlas-a.jar","ysmVersion":"a","loader":"fabric"},
{"fileName":"atlas-b.jar","ysmVersion":"b","loader":"fabric"},
{"fileName":"atlas-c.jar","ysmVersion":"c","loader":"other"}]}
'@, [Text.UTF8Encoding]::new($false))
    foreach ($name in @("atlas-a.jar", "atlas-b.jar", "atlas-c.jar")) {
        [IO.File]::WriteAllBytes((Join-Path $root "local-ysm/$name"), [byte[]]@(0))
    }
    & $git init -q -b main $root
    Git @("config", "user.name", "Private Fixture")
    Git @("config", "user.email", "fixture@example.invalid")
    Git @("add", ".gitignore", "fixtures")
    Git @("commit", "-q", "-m", "fixture base")

    $inventory = Invoke-Fixture -Operation Inventory
    Assert-True ($inventory.complete -and $inventory.actualCount -eq 3) "Catalog-driven inventory did not pass."
    Assert-True ($inventory.minecraftVersion -eq "9.9.9") "Profile Minecraft version was not reported."
    Assert-True ((Invoke-Fixture -Operation Audit).success) "Clean private artifact audit failed."

    $official = "local-ysm/atlas-a.jar"
    Git @("add", "-f", "--", $official)
    Assert-True ((Invoke-Fixture -Operation Audit -ExpectFailure).error -match "Private artifact") "Tracked official JAR was not rejected."
    Git @("reset", "-q", "HEAD", "--", $official)

    [IO.File]::WriteAllBytes((Join-Path $root "foreign.jar"), [byte[]]@(0))
    Git @("add", "-f", "--", "foreign.jar")
    Assert-True ((Invoke-Fixture -Operation Audit -ExpectFailure).error -match "Private artifact") "Tracked non-wrapper JAR was not rejected."
    Git @("reset", "-q", "HEAD", "--", "foreign.jar")

    $leak = New-LeakingJar -Entry @("com/elfmcys/yesstevemodel/Private.class", "native/private.dll", "private-reports/runtime-names.json")
    Assert-True ((Invoke-Fixture -Operation Audit -ExpectFailure).error -match "jarViolations=1") "Private distribution content was not rejected."
    Remove-Item -LiteralPath $leak -Force
    Assert-True ((Invoke-Fixture -Operation Audit).success) "Audit did not recover after removing fixture violations."

    [ordered]@{ operation = "TestPrivateFixtureWorkflow"; passed = $script:Passed; status = "PASS" } | ConvertTo-Json -Compress
} finally {
    if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue }
}
