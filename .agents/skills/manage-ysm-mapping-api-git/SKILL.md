---
name: manage-ysm-mapping-api-git
description: Inspect and manage YSM-Mapping-API Git state, task commit boundaries, active Minecraft branch worktrees, uncommitted main overlays, shared-to-version propagation, exact-path commits, and explicitly requested pushes. Use when repository work begins with pending changes, the user changes tasks, names a Minecraft version, requests a commit or push, or a feature or fix spans main and every actively maintained mc/* branch. Do not use for history rewriting or domain design policy.
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
- For a feature or fix explicitly intended for every active version, read main's `.agents/active-minecraft-branches.txt`; do not enumerate every local `mc/*`.

## Mutating workflow

- Completion alone never authorizes a commit.
- Use `Commit` only with exact task-owned paths, the expected branch, an imperative message, an allowed authorization, and `-ConfirmExecution`.
- A task-change authorization applies only to isolated single-branch work. Cross-cutting work requires an explicit commit instruction.
- Before new active cross-version work, finish an isolated, independently owned pending main task under the normal task-boundary rule. Stop for user-owned, inseparable, or indeterminate pending work.
- Keep the primary worktree on `main`. Use `PrepareActiveWorktrees -Path <exact main task paths>` to overlay the task's uncommitted main changes onto branch-attached worktrees under the workspace `.worktrees` directory. Pass no paths only when main is clean and the task has no main-owned changes.
- Implement and validate version work against the overlay without committing it on `mc/*`. On explicit commit authorization, use `CaptureActiveWorktreeChanges`, commit the exact main paths on `main`, then run `MergeMain` with the capture manifest to replace overlays with normal `--no-ff` main merges and restore version-only changes.
- Commit restored version changes normally on each matching `mc/*`, run validation and audit, then use `CleanupActiveWorktrees`. It removes only clean managed worktrees without force and reports every retained worktree.
- One explicit cross-cutting commit instruction authorizes the known capture, main commit, main-to-active merges, version commits, comparison, and clean-worktree cleanup sequence.
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
& .\.agents\skills\manage-ysm-mapping-api-git\scripts\repository-workflow.ps1 -Operation PrepareActiveWorktrees -Path <main-paths> -Authorization ExplicitUser -ConfirmExecution
& .\.agents\skills\manage-ysm-mapping-api-git\scripts\repository-workflow.ps1 -Operation CaptureActiveWorktreeChanges -SnapshotPath <overlay-manifest>
& .\.agents\skills\manage-ysm-mapping-api-git\scripts\repository-workflow.ps1 -Operation MergeMain -SnapshotPath <capture-manifest> -Authorization ExplicitUser -ConfirmExecution
& .\.agents\skills\manage-ysm-mapping-api-git\scripts\repository-workflow.ps1 -Operation CleanupActiveWorktrees -Authorization ExplicitUser -ConfirmExecution
& .\.agents\skills\manage-ysm-mapping-api-git\scripts\repository-workflow.ps1 -Operation Validate
& .\.agents\skills\manage-ysm-mapping-api-git\scripts\repository-workflow.ps1 -Operation Audit
```

Before handoff, run `Validate` at task scope and `Audit`, then report the active branch, pending paths, validation logs, and any policy warnings.

After changing this Git skill or its paired history skill, run `scripts/verify-skill-parity.ps1` and `scripts/test-skill-parity.ps1`.
