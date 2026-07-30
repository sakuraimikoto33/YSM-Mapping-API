## Always-On Rules

- Never pass `autoResolutionMs` to `request_user_input`.
- Preserve user changes. Never discard, overwrite, stage, or commit unrelated work.
- Run `.agents/skills/manage-ysm-mapping-api-git/scripts/repository-workflow.ps1 -Operation Inspect` at the start of repository work; use its result before choosing a branch or editing.
- Use active cross-version worktrees only when task-owned files or hunks will be edited directly on at least two actively maintained `mc/*` branches. Read eligible branches from main's `.agents/active-minecraft-branches.txt`; a main change or main-to-Minecraft merge never counts as a direct Minecraft edit.
- Keep main-only work on `main`; after an authorized main commit, propagate it to every active `mc/*` with the worktree-free `PropagateMain` workflow. For qualifying multi-Minecraft edits, keep the primary worktree on `main`, overlay uncommitted main-owned changes only into the explicitly selected branch worktrees, and later replace the overlay with normal `--no-ff` main merges before version commits.
- Treat an explicit request naming a Git or irreversible operation as approval for its known normal workflow. Stop on conflicts, validation failures, unexpected scope, or newly discovered irreversible effects.
- Completion alone never authorizes a commit. Load `$manage-ysm-mapping-api-git` for pending work, task changes, Minecraft branches, commits, merges, or pushes.
- Load `$rewrite-ysm-mapping-api-history` only for an explicit amend, squash, rebase, or history-rewrite request.
- Load `$maintain-ysm-mapping-contract` only for public API, manifest, cache, fingerprint, definition, or contract-version work.
- Load `$analyze-private-ysm-fixtures` only for ignored YSM JAR fixtures or private-derived reports.
- Never bump a mod/release, public API, manifest/cache schema, fingerprint algorithm, or definition revision without an explicit instruction. Minecraft and dependency versions follow the Git skill's separate rules.
- Never track or distribute proprietary YSM artifacts or private-derived runtime names, graphs, native libraries, or decompiler output.
- Do not push without an explicit remote/ref instruction. Ordinary push permission never authorizes force or force-with-lease.
- After changing Git or history instruction assets, run `.agents/skills/manage-ysm-mapping-api-git/scripts/verify-skill-parity.ps1` and its fixture.
