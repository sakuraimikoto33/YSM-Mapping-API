# Private artifact policy

Official YSM JARs are local regression inputs only. Keep `local-ysm/`, `ysm-analysis/`, and `test-fixtures/private/` ignored. Never track or distribute YSM JARs, classes, native libraries, decrypted assets, decompiler output, runtime-name catalogs, whole-JAR graphs, or reports containing those values.

Safe tracked material is limited to original analysis code, semantic definitions that do not publish runtime names, synthetic tests, and documentation that describes behavior without private-derived identifiers.

Write aggregate registry reports to ignored `build/reports`. Write explicitly requested raw graph or name investigations only to `build/reports/private`. Do not move them into docs, resources, test fixtures, or final JARs. Report counts, pass/fail status, and semantic gaps; do not quote raw runtime names.
