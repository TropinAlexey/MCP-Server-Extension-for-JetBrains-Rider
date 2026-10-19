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

## Tool names and actions

Tool and action names are part of the contract: clients pin them in Exposed
Tools allowlists, agent prompts and scripts.

1. Renaming or removing a `rider_*` tool, or an `action=` value, is a breaking
   change: major version bump + migration note (old → new name) in README What's New.
2. A merge (two tools → one tool with actions) counts as removal of the old names.
   Without a major bump the old tool stays registered for one minor release as a
   thin alias that forwards to the new action and says in its description which
   name replaces it.
3. Adding a tool, an action or an optional parameter is additive and needs no major bump.
4. History: the 09.2026 merges (`rider_run_tests` → `rider_tests(action=…)` etc.)
   shipped in minor 1.1.x without aliases; this rule exists because of them.

## IDE version lines

The Marketplace serves each Rider build the newest plugin update whose
`since-build`/`until-build` range contains it; older updates stay published.
A Rider that a new line drops keeps getting the last update of the old line.

1. A plugin line targets one Rider range: it compiles against the oldest Rider
   of that range (`rider("…")` in `build.gradle.kts`), and `sinceBuild` matches it.
2. While one build works on every supported Rider, keep a single line. Bridging an
   API difference (e.g. the IDE action instead of `BuildParameters`, whose
   constructor differs between 261 and 262) is preferred over splitting.
3. A new line starts only with a major version. Planned: 2.0 = `sinceBuild = "262"`,
   compiled against Rider 2026.2, together with the breaking tool renames/removals
   of that release. 1.2.x stays for Rider 2026.1.
4. When a line is split, the old one is frozen on `release/<major>.<minor>.x` with
   `untilBuild` set (e.g. `"261.*"`); only critical fixes go there, released by a
   `v*` tag from that branch. New features go to the current line only.
5. Evals measure the line of the installed Rider (`runIdeEvals` uses it). A release
   of the old line needs its own check on a Rider of that range.

## DB layer boundary

Our DB tools cover what the built-in database tools cannot do safely or at all;
schema browsing stays with the built-in server.

| Ours (`rider_*`) | Built-in (do not duplicate) |
|---|---|
| Executing SQL via an open console: `rider_execute_console` (own JDBC connection, T-SQL batches, server errors with code/SQLState, PRINT/RAISERROR, timeouts, file export) | Schema introspection: `list_database_schemas`, `list_schema_objects`, `get_database_object_description`, `introspect_schema` |
| Safety: Read-only guard, rolled-back/read-only sessions, secret masking | Table preview: `preview_table_data` |
| Long queries: `rider_console_job` (async, detach, cancel, progress messages) | Connection listing/testing: `list_database_connections`, `test_database_connection` |
| What the user ran in the IDE: `rider_console_results`, `rider_list_db_consoles` | |
| Data source management with working MSSQL matching, Read-only flag, open console/table: `rider_database_connection` | |

Moratorium: no new DB tool until this table changes in a reviewed commit. A new DB
need first goes into an action or recipe of the five tools above; if a built-in
tool covers it, AGENTS.md routes to the built-in tool instead.

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

- Built-in overlap audit, R13/R13b (10.2026, `evals.py run --hide`): agents never chose `rider_build`
  (S1, 12 runs → built-in `build_solution_*`) nor `rider_debug`/`rider_breakpoint`/`rider_start_debug`
  (S5 → built-in `xdebug_*`); hiding them kept 3/3 PASS. CANDIDATES FOR REMOVAL in 2.0 (breaking → major,
  see "Tool names and actions"). `rider_tool_window` KEEP: hidden, agents fell back to a misroute.
- Second audit, R14/R14b (10.2026): `rider_start_debug` (S23 → built-in `execute_run_configuration`),
  `rider_send_terminal_input` + `rider_list_terminals` (S24 → built-in `execute_terminal_command`) never chosen;
  hiding them kept 3/3 PASS. REMOVE in 2.0 together with `rider_build`, `rider_debug`, `rider_breakpoint`.
  `rider_run_config` KEEP: after the `copyFrom` fix 3 calls vs 4–7 for the built-in workaround (hand-written XML
  in `.idea/runConfigurations`).

## Response audit log

- Empty-collection convention (09.2026): `rider_list_db_consoles` answers `[]`
  (bare array) when no console is open, otherwise `{"count", "consoles": [...]}`.
  `rider_tool_window(action='content')` answers an object with `text` (`"(empty)"`
  when blank) plus `availableSections`/`otherTabs` for navigation. Consumers must
  handle both shapes. Changing them would break released clients, so they stay.
  (History: `rider_get_todos` merged into `rider_tool_window(windowId='TODO')`; `rider_get_endpoints` into `windowId='Endpoints'`.)
