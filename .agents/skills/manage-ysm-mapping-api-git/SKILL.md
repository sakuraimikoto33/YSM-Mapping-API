---
name: manage-ysm-mapping-api-git
description: Inspect and manage YSM-Mapping-API Git state, task commit boundaries, worktree-free main propagation, selected multi-Minecraft worktrees, uncommitted main overlays, exact-path commits, and explicitly requested pushes. Use when repository work begins with pending changes, the user changes tasks, names a Minecraft version, requests a commit or push, or task-owned edits are required on one or more mc/* branches. Do not use for history rewriting or domain design policy.
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
- Count only task-owned file or semantic-hunk changes made directly on `mc/*`. A main-owned change or main merge does not count as a Minecraft edit.

## Mutating workflow

- Completion alone never authorizes a commit.
- Use `Commit` only with exact task-owned paths, the expected branch, an imperative message, an allowed authorization, and `-ConfirmExecution`.
- A task-change authorization applies only to isolated single-branch work. Cross-cutting work requires an explicit commit instruction.
- For main-only work, remain on `main`, commit normally when authorized, then run `PropagateMain`. It prevalidates every required active merge in temporary local clones, creates no Git worktree, merges `main` sequentially with `--no-ff`, and returns to `main`.
- Before a task with direct edits on at least two active `mc/*` branches, finish an isolated, independently owned pending main task under the normal task-boundary rule. Stop for user-owned, inseparable, or indeterminate pending work.
- Keep the primary worktree on `main`. Use `PrepareActiveWorktrees -MinecraftBranch <two-or-more-active-branches> -Path <exact main task paths>` to overlay uncommitted main changes only into the explicitly selected branch-attached worktrees. Do not call it for zero or one direct Minecraft edit.
- Implement and validate direct version work against the overlay without committing it. `CaptureActiveWorktreeChanges` requires a version-owned or mixed-path diff on every selected branch; main overlays and merges cannot satisfy this gate.
- On explicit commit authorization, capture the worktrees, commit exact main paths when present, run `MergeMain` to replace selected overlays with normal `--no-ff` main merges, commit restored version changes, run `PropagateMain` for any remaining active branches, validate and audit, then use `CleanupActiveWorktrees`.
- One explicit multi-Minecraft commit instruction authorizes the known capture, optional main commit, main propagation, version commits, comparison, and clean-worktree cleanup sequence.
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
& .\.agents\skills\manage-ysm-mapping-api-git\scripts\repository-workflow.ps1 -Operation PropagateMain -Authorization ExplicitUser -ConfirmExecution
& .\.agents\skills\manage-ysm-mapping-api-git\scripts\repository-workflow.ps1 -Operation PrepareActiveWorktrees -MinecraftBranch <branches> -Path <main-paths> -Authorization ExplicitUser -ConfirmExecution
& .\.agents\skills\manage-ysm-mapping-api-git\scripts\repository-workflow.ps1 -Operation CaptureActiveWorktreeChanges -SnapshotPath <overlay-manifest>
& .\.agents\skills\manage-ysm-mapping-api-git\scripts\repository-workflow.ps1 -Operation MergeMain -SnapshotPath <capture-manifest> -Authorization ExplicitUser -ConfirmExecution
& .\.agents\skills\manage-ysm-mapping-api-git\scripts\repository-workflow.ps1 -Operation CleanupActiveWorktrees -Authorization ExplicitUser -ConfirmExecution
& .\.agents\skills\manage-ysm-mapping-api-git\scripts\repository-workflow.ps1 -Operation Validate
& .\.agents\skills\manage-ysm-mapping-api-git\scripts\repository-workflow.ps1 -Operation Audit
```

Before handoff, run `Validate` at task scope and `Audit`, then report the active branch, pending paths, validation logs, and any policy warnings.

After changing this Git skill or its paired history skill, run `scripts/verify-skill-parity.ps1` and `scripts/test-skill-parity.ps1`.
