---
name: maintain-ysm-mapping-contract
description: Maintain and validate YSM-Mapping-API shared api-core contracts, Minecraft-owned public Java API behavior, semantic keys, requests-v1.json manifests, cache and settings documents, fingerprint algorithms, definition revisions, resolution policy, and contract documentation. Use for public mapping interfaces, manifest parsing, persisted mapping formats, digest behavior, curated definitions, or explicit contract-version changes. Do not use for Git workflow or private fixture handling alone.
---

# Maintain YSM Mapping Contract

Read [contract-policy.md](references/contract-policy.md) before changing a public or persisted contract.

## Workflow

1. Identify affected consumers, public types, manifests, stored documents, digests, and tests.
2. Run `scripts/verify-mapping-contract.ps1` before editing.
3. Keep `api-core` contracts shared and validate the Minecraft-owned `api` and persisted `common` contracts when those modules exist on the active branch.
4. Keep implementation, tests, examples, request resources, README, and distribution checks consistent.
5. Run the verifier and repository `Validate` after editing.
6. Report compatibility effects and every explicitly authorized contract-version change.

Breaking unreleased changes replace the active v1 behavior in place. Do not add v2 readers, files, compatibility shims, migration history, or parallel caches unless the user explicitly requests that contract-version change.

```powershell
& .\.agents\skills\maintain-ysm-mapping-contract\scripts\verify-mapping-contract.ps1
```
