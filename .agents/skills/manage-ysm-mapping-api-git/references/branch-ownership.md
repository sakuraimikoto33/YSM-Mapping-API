# Branch ownership

Read this reference only for ownership questions, mixed root files, or work spanning main and mc branches.

## Main

Commit repository instructions, `.agents/`, `.gitignore`, Gradle wrapper files, shared Gradle foundations, `api-core/`, `analysis-core/`, shared mapping tools, and shared API/analysis/tooling documentation on `main`. In particular, edit and commit `.agents/active-minecraft-branches.txt` only on `main`; version branches receive it only through a main merge.

## Minecraft branches

Commit `api/`, `common/`, `fabric/`, `forge/`, `neoforge/`, Minecraft-specific documentation, mapping profiles and fixture expectations, loader integration, and Minecraft-specific configuration on the matching `mc/<minecraft-version>` branch.

## Mixed files

Treat `README.md`, root `build.gradle.kts`, `settings.gradle.kts`, and `gradle.properties` by semantic hunk. Shared foundations belong to `main`; Minecraft, loader, runtime distribution, or composite-build hunks belong to the target `mc/*`.

If path classification and semantic ownership disagree, treat the result as mixed and inspect it manually. Scripts must not decide semantic hunk ownership or resolve conflicts.
