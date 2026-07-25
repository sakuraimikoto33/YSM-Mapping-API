---
name: manage-ysm-mapping-api-git
description: Inspect and manage YSM-Mapping-API Git state, task commit boundaries, Minecraft branches, shared-to-version propagation, exact-path commits, and explicitly requested pushes. Use when repository work begins with pending changes, the user changes tasks, names a Minecraft version, requests a commit or push, or work spans main and mc/* branches. Do not use for history rewriting or domain design policy.
---

# Manage YSM-Mapping-API Git

Use the scripts as the source of deterministic repository state. Do not read script bodies before running them.

## Start and classify

1. Run `scripts/repository-workflow.ps1 -Operation Inspect` from the repository root.
2. Preserve unrelated changes and stop if the task cannot be isolated safely.
3. Run `Classify -Path <exact paths>` before editing when branch ownership is not obvious.
4. Read [branch-ownership.md](references/branch-ownership.md) only for mixed or cross-branch work.
5. Read [task-boundaries.md](references/task-boundaries.md) only when pending work, a new task, or a commit is involved.

## Select Minecraft work

- Change Minecraft branches only when the user explicitly names a Minecraft version.
- Run `PrepareMinecraftBranch -MinecraftVersion <version>` first. If the branch is absent, present the returned base candidates and ask which branch to use without `autoResolutionMs`.
- After the user selects the base, rerun with `-BaseBranch <branch> -Authorization ExplicitUser -ConfirmExecution`.
- Never infer a target Minecraft version from source, Gradle properties, or nearby branches.

## Mutating workflow

- Completion alone never authorizes a commit.
- Use `Commit` only with exact task-owned paths, the expected branch, an imperative message, an allowed authorization, and `-ConfirmExecution`.
- A task-change authorization applies only to isolated single-branch work. Cross-cutting work requires an explicit commit instruction.
- For cross-cutting work, use `Snapshot`, commit shared hunks on `main`, run `MergeMain` for every local `mc/*`, restore and commit version hunks, then run `CompareSnapshot`. One explicit commit instruction authorizes this known sequence.
- Merge only `main` into local `mc/*`. Never merge `mc/*` into `main`.
- Use `Push` only for an explicitly named remote and refspec. Force modes require separate explicit force permission.
- Stop on a conflict, validation failure, unexpected path/ref, snapshot mismatch, or newly discovered irreversible effect.

## Versions and dependencies

- A named Minecraft version authorizes branch selection or creation; it is not a product version bump.
- Keep loader, library, Gradle plugin, and toolchain versions fixed unless the requested feature, fix, compatibility work, or build repair requires the smallest viable update. Record the evidence and validate it on the owning `mc/*` branch.
- Pass that evidence with `-DependencyVersionReason <reason>` when `Validate`, `Commit`, or `MergeMain` sees a dependency-version diff.
- Do not opportunistically update dependencies.
- Product contract versions require explicit authorization; the workflow script detects guarded changes.

## Commands

```powershell
& .\.agents\skills\manage-ysm-mapping-api-git\scripts\repository-workflow.ps1 -Operation Inspect
& .\.agents\skills\manage-ysm-mapping-api-git\scripts\repository-workflow.ps1 -Operation Classify -Path <paths>
& .\.agents\skills\manage-ysm-mapping-api-git\scripts\repository-workflow.ps1 -Operation Validate
& .\.agents\skills\manage-ysm-mapping-api-git\scripts\repository-workflow.ps1 -Operation Audit
```

Before handoff, run `Validate` at task scope and `Audit`, then report the active branch, pending paths, validation logs, and any policy warnings.

After changing this Git skill or its paired history skill, run `scripts/verify-skill-parity.ps1` and `scripts/test-skill-parity.ps1`.
