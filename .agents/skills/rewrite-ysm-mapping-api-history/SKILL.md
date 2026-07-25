---
name: rewrite-ysm-mapping-api-history
description: Safely prepare, back up, and verify an explicitly requested YSM-Mapping-API Git history rewrite such as amend, squash, rebase, commit reconstruction, or retroactive correction. Use only when the user explicitly requests rewriting existing history. Do not use for ordinary commits, merges, branch creation, or pushes.
---

# Rewrite YSM-Mapping-API History

History rewriting is a critical Git operation. The explicit rewrite request authorizes the described rewrite, but not any push.

## Workflow

1. Read [rewrite-policy.md](references/rewrite-policy.md).
2. Run `scripts/history-safety.ps1 -Operation Inspect -Branch <affected branches>`.
3. Resolve dirty state and affected-branch ambiguity without discarding user work.
4. Run `Snapshot` and retain its temp manifest path.
5. Review every proposed archive name. Run `CreateBackups -ConfirmExecution` only after all names and tips match the request.
6. Perform the requested rewrite manually; do not automate semantic commit reconstruction or conflict resolution.
7. Run `Compare` against the manifest before moving on. Report and preserve every difference.
8. Run the repository Git skill's `Audit`. A rewrite instruction never authorizes a push.

## Stop conditions

- Stop before creating any backup if one proposed name already exists.
- Stop on dirty or in-progress Git state that the user did not explicitly include.
- Stop if an affected branch, worktree, stash, archive tip, active tree, or file-state comparison is unexpected.
- Never delete, reuse, rename, or overwrite an archive branch.

```powershell
& .\.agents\skills\rewrite-ysm-mapping-api-history\scripts\history-safety.ps1 -Operation Inspect -Branch <affected branches>
```
