# Tool Response Contract

Public commitment: scripts and agents built on `rider_*` tools must not break
between releases.

## Rules (additive-only)

1. Response fields are only **added**, never renamed, retyped, or removed.
2. New fields are optional in spirit: old consumers that ignore them keep working.
3. `status` values keep their meaning (`running` + terminal states per tool docs).
4. A new tool is preferable to changing an existing tool's response shape.
5. Exceptions (breaking changes) require a major version bump and a migration
   note in README What's New.

## Recipes over tools

A scenario covered by a cookbook description in an existing tool does not get
a new tool. A new `rider_*` tool is justified only when all of these hold:

1. The need cannot be met by a richer `McpDescription`/recipe on an existing tool.
2. The reason is recorded in the commit message.
3. Eval delta (notes/evals) does not regress: calls, misroutes, first-try rate.

## Merger audit log

- `rider_run_tests` vs `rider_run_tests_and_wait` (09.2026): KEEP BOTH.
  Different needs — fire-and-poll for long runs where the agent does other
  work vs single-call run+wait. Revisit on eval data, not on taste.
