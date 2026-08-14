<!-- BEGIN MANAGED: minecraft-mod-agent-workflows -->
## Shared Agent Rules

- Preserve user changes. Never discard, overwrite, stage, or commit unrelated work.
- Run `.agents/skills/manage-minecraft-mod-git/scripts/repository-workflow.ps1 -Operation Inspect` before choosing a branch or editing.
- Load `$manage-minecraft-mod-git` for pending work, task changes, branch selection, commits, merges, validation, or pushes. Completion alone never authorizes a commit.
- Treat an explicit request naming a Git or irreversible operation as approval for its known normal workflow. Stop on conflicts, validation failures, unexpected scope, or newly discovered irreversible effects.
- Load `$rewrite-minecraft-mod-history` only for an explicit amend, squash, rebase, reset, commit reconstruction, or other history-rewrite request.
- Do not push without an explicit remote and ref instruction. Ordinary push permission never authorizes force or force-with-lease.
- Centrally managed blocks and common Skill files must be changed in Minecraft-Mod-Agent-Workflows and synchronized; do not hand-edit generated copies.

## User Input

- When calling the `request_user_input` tool, never set `autoResolutionMs`. Wait for the user to answer explicitly.
<!-- END MANAGED: minecraft-mod-agent-workflows -->

## Repository Rules

- Load `$maintain-ysm-mapping-contract` only for public API, manifest, cache, fingerprint, definition, or contract-version work.
- Load `$analyze-private-ysm-fixtures` only for ignored YSM JAR fixtures or private-derived reports.
- Never bump a mod/release, public API, manifest/cache schema, fingerprint algorithm, or definition revision without an explicit instruction. Minecraft and dependency versions follow the Git skill's separate rules.
- Never track or distribute proprietary YSM artifacts or private-derived runtime names, graphs, native libraries, or decompiler output.
