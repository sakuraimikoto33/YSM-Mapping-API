## Always-On Rules

- Never pass `autoResolutionMs` to `request_user_input`.
- Preserve user changes. Never discard, overwrite, stage, or commit unrelated work.
- Run `.agents/skills/manage-ysm-mapping-api-git/scripts/repository-workflow.ps1 -Operation Inspect` at the start of repository work; use its result before choosing a branch or editing.
- Treat a feature or fix intended for every actively maintained Minecraft version as active cross-version work. Read the authoritative branches from main's `.agents/active-minecraft-branches.txt`; never substitute every local `mc/*`.
- For active cross-version work, keep the primary worktree on `main`, prepare branch-attached worktrees with the task's uncommitted main-owned changes overlaid, and later replace that overlay with a normal `--no-ff` main merge before version commits.
- Treat an explicit request naming a Git or irreversible operation as approval for its known normal workflow. Stop on conflicts, validation failures, unexpected scope, or newly discovered irreversible effects.
- Completion alone never authorizes a commit. Load `$manage-ysm-mapping-api-git` for pending work, task changes, Minecraft branches, commits, merges, or pushes.
- Load `$rewrite-ysm-mapping-api-history` only for an explicit amend, squash, rebase, or history-rewrite request.
- Load `$maintain-ysm-mapping-contract` only for public API, manifest, cache, fingerprint, definition, or contract-version work.
- Load `$analyze-private-ysm-fixtures` only for ignored YSM JAR fixtures or private-derived reports.
- Never bump a mod/release, public API, manifest/cache schema, fingerprint algorithm, or definition revision without an explicit instruction. Minecraft and dependency versions follow the Git skill's separate rules.
- Never track or distribute proprietary YSM artifacts or private-derived runtime names, graphs, native libraries, or decompiler output.
- Do not push without an explicit remote/ref instruction. Ordinary push permission never authorizes force or force-with-lease.
- After changing Git or history instruction assets, run `.agents/skills/manage-ysm-mapping-api-git/scripts/verify-skill-parity.ps1` and its fixture.
