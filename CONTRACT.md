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

## Docs sync (enforced by `checkToolDocs`)

- Single source of truth for tool names is `@McpTool suspend fun rider_*` in code.
  `README.md` must name every tool in backticks; `AGENTS.md` routes by those names.
- Cross-references in descriptions/runtime errors may name only tools that exist:
  `rider_*` from this repo or built-in server tools explicitly marked "built-in"
  (with an IDE fallback when the client may not expose them).
- Description block budget: quoted `@McpDescription` text per tool ≤ 1100 chars
  (routing detail lives in `AGENTS.md`, not inline).
- Destructive actions (kill, terminal input, SQL writes, cache restart, config
  delete, memory kill) must say so and require user confirmation in the text —
  descriptions are guidance, enforcement is the client's Exposed Tools allowlist.

## Merger audit log

- `rider_tests(action='run')` vs `rider_tests(action='run_and_wait')` (09.2026): KEEP BOTH actions in one tool.
  Different needs — fire-and-poll for long runs where the agent does other
  work vs single-call run+wait. Revisit on eval data, not on taste.
  (History: separate `rider_run_tests` / `rider_run_tests_and_wait` tools merged into actions; same for
  `rider_start_build`+`rider_cancel_build` → `rider_build`, `rider_profiling_state`+`rider_profiling_control` → `rider_profiling`, etc.)

## Response audit log

- Empty-collection convention (09.2026): `rider_list_db_consoles` answers `[]`
  (bare array) when no console is open, otherwise `{"count", "consoles": [...]}`.
  `rider_tool_window(action='content')` answers an object with `text` (`"(empty)"`
  when blank) plus `availableSections`/`otherTabs` for navigation. Consumers must
  handle both shapes. Changing them would break released clients, so they stay.
  (History: `rider_get_todos` merged into `rider_tool_window(windowId='TODO')`; `rider_get_endpoints` into `windowId='Endpoints'`.)
