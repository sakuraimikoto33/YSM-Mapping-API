# Task and commit boundaries

Read this reference only when work is already pending, the user changes tasks, or a commit is requested.

## Decide the boundary

A commit boundary exists when:

1. The user explicitly requests a commit.
2. Work A is pending and the user requests an independent implementation or correction B.

Do not commit because implementation, review, correction, or validation finished. Continue uncommitted for A', for a correction required to make A acceptable, and for B caused or exposed by A.

## Classify a requested B

Freeze staging, commits, and edits to B while classifying.

1. Inspect A's diff and baseline. Trace changed APIs, state, configuration, formats, and ordering.
2. Define B's symptom, reproducer, expected result, and failing path.
3. Compare with A applied and, when feasible, at A's baseline in a temporary worktree.
4. Classify B as `A-derived`, `A-required`, `independent`, or `indeterminate`.

Keep A and B together for `A-derived` or `A-required`. Commit isolated A before editing an `independent` B. For `indeterminate`, present the evidence and ask; edit nothing until answered. Timing and file overlap are not proof.

## Cross-cutting boundary

A task-change instruction does not authorize splitting pending main/mc work. Ask for an explicit commit instruction.

For a new feature or fix intended for every branch listed in main's `.agents/active-minecraft-branches.txt`, finish a preceding independent main task before starting. Keep the new task's main-owned changes uncommitted, overlay only its exact paths onto managed branch worktrees, and do not commit the overlay on `mc/*`.

Once an explicit cross-cutting commit instruction is received, it authorizes the normal active-worktree capture, exact main commit, verified `--no-ff` main-to-active merges, version-diff restoration and commits, comparison, and clean-worktree cleanup sequence. Stop rather than improvising if any manifest, ref, tree, worktree association, or preflight result differs.
