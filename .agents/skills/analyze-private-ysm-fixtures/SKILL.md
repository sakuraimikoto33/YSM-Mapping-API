---
name: analyze-private-ysm-fixtures
description: Inventory and analyze catalog-defined ignored official YSM JAR regression fixtures, generate profile-driven safe registry reports, and audit Git and distributions for proprietary or private-derived artifacts. Use for local-ysm fixture validation, registry-report runs, local name or graph investigations, proprietary-content checks, or private report cleanup. Do not use for ordinary public mapping API changes.
---

# Analyze Private YSM Fixtures

Read [private-artifact-policy.md](references/private-artifact-policy.md) before producing any derived report.

## Safe workflow

1. Select the Minecraft-owned analysis profile and fixture catalog for the requested branch.
2. Run `scripts/private-fixture-workflow.ps1 -Operation Inventory -ProfilePath <profile> -CatalogPath <catalog>`.
3. Require the catalog-defined ignored official fixture set before regression analysis.
4. Use `RegistryReport` with the same profile and catalog for the safe aggregate report. It writes only under ignored `build/reports`, runs Gradle offline, and returns a compact parsed result.
5. Use `Audit` with the same profile and catalog before handoff to confirm fixtures and private-derived output are untracked and absent from distributions.
6. Never paste or persist runtime names, full graphs, decompiler output, native libraries, or proprietary class content in tracked files or conversation output.

`Graph` and `name-report` investigations require an explicit task naming them. Keep their outputs under `build/reports/private`; summarize conclusions without exposing raw private-derived data.

```powershell
& .\.agents\skills\analyze-private-ysm-fixtures\scripts\private-fixture-workflow.ps1 -Operation Inventory -ProfilePath <profile> -CatalogPath <catalog>
& .\.agents\skills\analyze-private-ysm-fixtures\scripts\private-fixture-workflow.ps1 -Operation RegistryReport -ProfilePath <profile> -CatalogPath <catalog>
& .\.agents\skills\analyze-private-ysm-fixtures\scripts\private-fixture-workflow.ps1 -Operation Audit -ProfilePath <profile> -CatalogPath <catalog>
```
