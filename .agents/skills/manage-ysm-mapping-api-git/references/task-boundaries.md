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

A task-change instruction does not authorize splitting pending main/mc work. Ask for an explicit commit instruction. Once received, it authorizes the normal snapshot, main commit, main-to-mc merges, version commits, and comparison sequence. Stop rather than improvising if any step differs from the preflight result.
