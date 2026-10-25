# Rider MCP — Agent Guide

MCP Server extension plugin for JetBrains Rider. Adds `rider_*` tools for what the built-in MCP server does not cover: test runner, DB consoles, tool windows, IDE state and context, run config CRUD, NuGet, Publish, profiling sessions, and more. Build, debugger and terminal are the built-in tools.

Per-tool documentation lives in each tool's description (visible on tool call). This file is the routing layer — which tool for which task.

Working on this repository's code rather than using its tools? Contributor rules are in [CONTRIBUTING.md](CONTRIBUTING.md).

## Prefer `rider_*` over base MCP Server tools

The base JetBrains MCP Server (`com.intellij.mcpServer`) ships its own database tools. **Use the `rider_*` versions instead** — they handle more DBMS types and edge cases:

| Task | Use this | NOT this |
|---|---|---|
| Create DB connection | `rider_database_connection(action='create')` | `create_database_connection` (rejects every MSSQL JDBC URL as of Rider 2026.2) |
| Edit DB connection | `rider_database_connection(action='edit')` | `edit_database_connection` |
| Execute SQL | `rider_execute_console` (via open console) | `execute_sql_query` (hides driver errors as "Unknown error", 10-row pages, no file export, no T-SQL batches, no timeout control, requires schema introspection — DMV/server queries fail with "Schema … is not found", and a database the login cannot access is reported the same way; use `rider_execute_console(database='master')`) |
| Results the user ran in a console | `rider_console_results` | `list_recent_sql_queries` / `fetch_query_result` (no result set ids for console runs), `rider_tool_window(section=…)` on Services (ambiguous titles) |

## Task → Tool Map

- Cursor, selection, open files, bookmarks → `rider_get_context`
- Recently opened files → built-in `get_all_open_file_paths` (open editors) or VCS log for broader history
- Paths for built-in file tools (`read_file`, `list_directory_tree`, …) are relative to `projectDir` from `rider_get_ide_state` (the solution root), not to the shell cwd; `/` is rejected — use `.` for the root
- IDE readiness (indexing, busy) → `rider_get_ide_state` (call before heavy work)
- IDE errors and warnings → `rider_get_output` (session output) or `rider_tool_window(action='content', windowId='Problems')`
- Solution-wide error count that does not match the Problems panel → `rider_swea_errors`
- Workspace build configuration, phantom errors after Publish, or mismatched RID/platform → `rider_workspace_config`
- Any panel content (Build, Problems, etc.) → `rider_tool_window` (actions: `list`, `tabs`, `content` — see **Pagination** below and **Tool window map**)
- Debugged app stdout (NOT the debugger trace) → `rider_tool_window(action='content', windowId='Debug', section='Console', fromEnd=true)`
- TODO/FIXME/HACK in code → `rider_tool_window(action='content', windowId='TODO')`
- API endpoints → built-in `search_symbol` or `get_service_map`; fallback: `rider_tool_window(action='content', windowId='Endpoints')`
- IDE terminal commands → built-in `execute_terminal_command` (returns the output)
- Build → built-in `build_solution_start` + `build_solution_state` (errors with file/line). Files just written outside the IDE: check `get_file_problems` on them first — a build fired before the IDE sees the file can report success
- Publish → `rider_publish` (`configName?`) + `rider_get_output`
- Run tests → `rider_tests` (actions: `run`, `run_and_wait`, `results`, `rerun_failed`) + `rider_get_output` (`configName` alone runs the whole config — scope with filters)
  - Long runs (Testcontainers, integration suites) → `run_and_wait` waits at most 50 s per call and answers `status=running`, `timedOut=true`, `elapsedMs`, `sessionId`; the run continues in the IDE. Call `rider_tests(action='run_and_wait', sessionId)` again until `status != running` — never a larger `timeoutMs` (calls over ~60 s are dropped as a client timeout) and never a fresh run
- Create/edit DB connections → `rider_database_connection` (actions: `create`, `edit`, `open_console`). Production source on the user's request → `readOnly=true`. The flag cannot be lifted through MCP (`readOnly=false` is rejected) — if a write is really intended, the user unticks Read-only in Rider. No console open for a connection → `action='open_console'` instead of asking the user to double-click. Show a table to the user → `action='open_table', table='db.schema.table'`. Built-in model tools answer "Schema … is not found" → `action='refresh'` (re-introspection; databases outside the source's Schemas scope stay invisible — the user ticks them)
- SQL in open DB consoles → `rider_list_db_consoles` + `rider_execute_console` (T-SQL scripts with `DECLARE @x …; … sp_executesql` run as one batch — split is by `GO` for MSSQL; slow scans → `timeoutSeconds`; huge cells → `maxCellLength`; next page → `offset` + `ORDER BY`)
  - Long queries (> ~40 s: cursors over databases, full scans) → `async=true` → `jobId`, then `rider_console_job(action='status', jobId, messagesOffset)` until `status != running`; cancel with `action='cancel'`. A sync call waits `waitSeconds` (default 50) and then cancels the server query (`onClientTimeout='cancel'`) or detaches it as a job (`'detach'`) — it never leaves an orphaned query behind. A busy console answers with its `jobId` at once.
  - Production / "read-only" requests → `readOnly=true` (forced for data sources marked Read-only — see `readOnly` in `rider_list_db_consoles`): DML/DDL rejected before sending, MSSQL runs in a rolled-back transaction, PostgreSQL in a read-only session; `#temp` / `@table` / `CREATE TEMP TABLE` allowed. Dynamic SQL must be literal (`sp_executesql N'…'`, `sp_MSforeachdb '… [?].sys.tables …'`) — SQL assembled in variables is rejected because it cannot be checked. Check `readOnlyEnforced` in the response.
  - Secrets: `maskSecrets` is on by default (password/secret/token columns, `password=` in connection strings). Turn it off only when the user explicitly needs the value.
  - More than a page of rows → raise `pageSize` (up to 5000; response has `columns`, `rowCount`, `hasMore`) — never glue rows with `STRING_AGG` to dodge paging.
  - Large exports, or data a script on disk will read → `outputFile='<path>.csv|.json|.txt'` (all rows, no page limit; `.txt` = one line per row, no header — a ready list of URLs/ids); the response keeps path, `columns` and a 5-row preview. Allowed dirs: project, the system temp dir (`java.io.tmpdir`, `/tmp`; an agent scratchpad only when it lies under one), `-Drider.mcp.exportDirs` or `RIDER_MCP_EXPORT_DIRS` (OS path separator). Existing files → `overwrite=true`.
  - Errors come verbatim with `[error N, SQLState …, severity …, line …]` (multi-batch runs: `errorCode`/`sqlState`/`severity`/`line`/`message` fields per failed batch). `Cannot switch to database …` carries the server's reason: 911 = no such database, 916 = the login has no access.
  - "The open table" / "the tab I have open" → `rider_list_db_consoles` → `tableEditors` (dataSource, database, schema, object; `selected=true` = active tab)
- Results of a script the USER ran in a console (Services → Database result tabs) → `rider_console_results(console, index?, outputFile?)`; `state=running` while it executes — poll it, the console is not blocked. `hasMoreInIde=true` = the IDE grid fetched only its first page
- NuGet packages → `rider_nuget` (actions: `list`, `add`, `remove`, `restore`)
- Run/Debug configurations → `rider_run_config` (actions: `create`, `update`, `delete`). New config for a .NET app with args/env → `create` with `copyFrom='<existing config of that project>'` + `programArgs`/`env` (a bare `typeId='DotNetProject'` config has no project and won't run; an unknown `typeId` lists the valid IDs). Built-in `get_run_configurations` lists existing configs with their args/env; launch with built-in `execute_run_configuration` (run) or `xdebug_start_debugger_session` (debug), tests via `rider_tests`
- Debugging → built-in `xdebug_*` (`xdebug_start_debugger_session`, `xdebug_set_breakpoint`, `xdebug_control_session`, `xdebug_get_frame_values`, `xdebug_evaluate_expression`). Remove the breakpoints you set (`xdebug_remove_breakpoint`) and resume the process before answering. App stdout of a debug session → `rider_tool_window(windowId='Debug', section='Console')`
- Run an app and read its output → built-in `execute_run_configuration` (`waitForExit=true` + `timeout` for a snapshot of a long-running app)
- IDE processes → `rider_list_processes` / `rider_kill_process`
- Code inspections → `rider_inspections` (actions: `list`, `toggle`)
- IDE caches → `rider_invalidate_caches`
- Profiling (dotTrace) → `rider_profiling` (actions: `state`, `control`)
- Profiling (dotMemory) → `rider_memory` (actions: `state`, `control`)

## Database consoles playbook

**Pick the database first.** `rider_list_db_consoles` → `consoles[]` (`consoleRef`, `currentDatabase`, `readOnly`, `busyJobId`) and `tableEditors[]` (open table/view tabs with `database`/`schema`/`object`; `selected=true` = active tab; `query` = how to query it — `rider_execute_console` on the same data source's console, not the built-in `execute_sql_query`). When the user says "the open console/table", take database and schema from here — never guess between similarly named databases.

**Run SQL yourself:**
- Always address consoles by `consoleRef` (`<data source>/<file>`): default consoles of different servers share the file name `console.sql`, and a bare ambiguous name is rejected.
- Short query (< ~40 s) → `rider_execute_console(sql, console)`. The sync call waits `waitSeconds` (default 50, keep it below the MCP client tool timeout); on expiry `onClientTimeout='cancel'` cancels the server query, `'detach'` keeps it running as a job. Nothing is left orphaned on the server.
- Long query (cursor over databases, full scans, `waitfor`) → `rider_execute_console(..., async=true, timeoutSeconds=<per batch>)` → `jobId`; poll `rider_console_job(action='status', jobId, messagesOffset=<nextMessagesOffset>, waitSeconds=30)` until `status != running` — `waitSeconds` blocks until the job ends or new messages arrive, so no tight polling loop; the finished status carries `result`. Stop with `action='cancel'`.
- Progress of long scripts: `raiserror('%s done', 0, 1, @x) with nowait` — appears in job `messages[]` with timestamps while running.
- Busy console error ("busy: jobId=…") → poll or cancel that job; do not retry blindly.
- Big outputs (thousands of rows, reports over many databases), or rows a script must consume → `outputFile='<path>.csv|.json|.txt'`, then read the file; the response keeps only path, rowCount, columns and a 5-row preview. Never retype rows from a response into a file.
- Which database ran it: the response's `database` is the session's real `DB_NAME()`; `rider_list_db_consoles` gives `currentDatabase` with `currentDatabaseSource` = `toolbar` | `url` | `login-default`.

**Production = read-only:**
- Pass `readOnly=true` on any production / "only SELECT" request (it is forced for data sources marked Read-only in Rider). Confirm `readOnlyEnforced: true` in the response.
- Allowed: SELECT, `#temp` tables, `@table` variables, `CREATE TEMP TABLE`, read-only system procedures (`sp_help*`, `sp_who*`, `sp_spaceused` …), literal dynamic SQL (`sp_executesql N'…'`, `EXEC('…')`, `sp_MSforeachdb '… [?].sys.tables …'`).
- Rejected before sending: DML/DDL on real tables, `COMMIT`, `SET IMPLICIT_TRANSACTIONS`, other procedures, and dynamic SQL assembled in variables (`exec sp_executesql @sql`) — rewrite cross-database loops with `sp_MSforeachdb` + a literal, or with three-part names.
- A column named like a keyword (`load`, `backup`) is rejected unquoted — quote it: `[load]`.
- If the user asks whether something changed, answer from facts and offer a verifying SELECT; never run DML to "check".

**Secrets:** `maskSecrets` is on by default (password/secret/token/api_key columns → `***`, `password=` inside connection strings). Disable only when the user explicitly needs the value, and never paste secrets into notes or chats.

**Results the user ran themselves** (Services → Database result tabs):
- `rider_console_results(console='<file>')` → every result grid of the last run as CSV (`columns`, `loadedRows`, `totalRows`, `hasMoreInIde`) plus the Output tail and `summary` ("N rows retrieved in …"). `state=running` → poll again; reading never blocks the console.
- `hasMoreInIde=true` → the IDE grid fetched only its first page; ask the user to raise the grid page size or re-run via `rider_execute_console(outputFile=…)`.
- Large grids → `outputFile`.
- The user's `#temp` tables live in their console session and are NOT reachable from `rider_execute_console` (separate connection) — read the final SELECTs via `rider_console_results`.
- Do not use `list_recent_sql_queries` / `fetch_query_result` for console runs (no result set ids), nor `rider_tool_window(section=…)` on Services (titles repeat across runs and servers).

**Run it in the user's console** (they want to see the grids in the IDE, or the script needs the console's own session — `#temp` tables, `SET` options): `rider_execute_console(console, sql, inIde=true)` puts `sql` into the console editor (undoable with ⌘Z; blank `sql` = run the console's current text) and runs the whole script like the user's Execute. It waits ≤40 s and returns `status=finished|running`; then read the grids with `rider_console_results`. Not available for Read-only data sources or `readOnly=true` (the IDE run cannot roll back) — there use the default mode or ask the user to run it; `async`/`outputFile`/`database`/`pageSize` are ignored. Ask before replacing a console text the user is editing.

**When the agent cannot run it** (MCP client timeout too short, user prefers to run it): write the script (temp tables + final SELECTs + `raiserror … with nowait` progress, a header saying it is read-only), ask the user to run it in the console, then read it with `rider_console_results`.

## Permission prompts

The plugin works with any MCP client; each client decides on its own whether a tool call needs the user's approval, and no server can grant itself that. When the user keeps approving IDE tool calls (or a call is denied by the client's permission settings), point them to **Rider → Tools → Configure Agent Permissions…**: it finds the clients connected to this IDE and, after the user reviews the changes, adds the rules (read-only tools by default, or all tools):

- Claude Code — `permissions.allow` in `.claude/settings.local.json` (project) or `~/.claude/settings.json`: `mcp__<server>__<tool>` per read-only tool, or `mcp__<server>` for all
- Codex — `default_tools_approval_mode = "writes"` (prompt only for tools not marked read-only) or `"approve"` under `[mcp_servers.<server>]` in `~/.codex/config.toml`
- Gemini CLI — `"trust": true` on the server entry in `settings.json` (whole server only)
- Others (Copilot CLI `--allow-tool '<server>'`, Cursor, VS Code, Windsurf, Junie) — "Always allow" in the client

Read-only `rider_*` tools declare `readOnlyHint`; `rider_kill_process`, `rider_invalidate_caches` and `rider_execute_console` declare `destructiveHint`. Never edit client permission files yourself — suggest the action and let the user decide.

## Polling Pattern

`rider_tests(action='run')`, `rider_tests(action='rerun_failed')`, `rider_nuget(action='restore')`, `rider_publish` return `sessionId` — poll via `rider_get_output` until `status != "running"`.

`rider_execute_console(async=true)` (or a sync call detached by `onClientTimeout='detach'`) returns `jobId` — poll via `rider_console_job(action='status')` until `status != "running"`.

## Pagination & Filtering

`rider_tool_window(action='content')` and `rider_get_output` share the same pagination parameters. Response always includes `totalLines` and `returnedRange` so you know exactly what you got and how much more is available.

**Parameters:**

| Param | Type | Description |
|-------|------|-------------|
| `maxLines` | int | Max lines to return (default 200 for tool windows, 0=unlimited for output) |
| `fromEnd` | bool | `true` → return **last** `maxLines` lines. **Use this by default** for Debug/Build logs — errors are at the end |
| `offset` | int? | Start from this line (0-based). Mutually exclusive with `fromEnd`. For page-by-page navigation |
| `pattern` | string? | Regex filter (case-insensitive). Only matching lines returned, prefixed with `[lineNo]`. E.g. `"error\|exception\|warn"` |

`rider_get_output` also has `allLines` (bool) — `true` reads ALL accumulated session lines, not just new since last poll.

**Response fields:**
- `totalLines` — total lines in the window/session (always present)
- `returnedRange` — `{ from, to }` — which lines were returned (0-based)
- `truncated` — `true` when more lines exist beyond what was returned
- `matchedLines` — number of lines matching `pattern` (only when `pattern` used)
- `availableSections` — sub-tab titles in the window content (`rider_tool_window` only, always present — use for the `section` param instead of guessing)

**Recipes:**

```jsonc
// Last 20 lines of debug output (90% of use cases)
{ "windowId": "Debug", "maxLines": 20, "fromEnd": true }

// Grep errors/exceptions from entire log
{ "windowId": "Debug", "pattern": "error|exception|warn", "maxLines": 50 }

// Page through output: lines 200-250
{ "windowId": "Debug", "offset": 200, "maxLines": 50 }

// After a Publish: get all accumulated output, last 30 lines
{ "sessionId": "publish_1", "maxLines": 30, "fromEnd": true, "allLines": true }

// Search for a specific class in test output
{ "sessionId": "test_1", "pattern": "MyService", "allLines": true }
```

**Best practices:**
- Always start with `fromEnd: true` for Debug/Build/Console output — useful info is at the bottom
- Use `pattern` to search for errors without downloading the entire log
- Check `totalLines` in the response — if the window has 10K lines, don't request all of them
- For Problems/TODO/tree windows — default (first N lines) is usually fine, no `fromEnd` needed

## Tool Window Map

Model: **window → tabs → Swing component tree**. Navigate: `rider_tool_window(action='list')` → `rider_tool_window(action='tabs', windowId=...)` → `rider_tool_window(windowId=..., tab=..., section=...)`.

| Window | Tabs | Sections inside |
|---|---|---|
| `Debug` | One per debug session (tab = session name) | `Threads & Variables`, `Console` (app stdout, via debugger API), `Debug Output` (assembly/thread trace) |
| `Run` | One per execution (tab = descriptor name) | Process stdout/stderr console |
| `Build` | Build sessions | Compiler output (structured errors also in `Problems`) |
| `Problems` | Scope | Errors/warnings tree |
| `Terminal` | One per terminal | Shell console |
| `TODO` | Scope filter | TODO/FIXME/HACK tree |
| `Endpoints` | — | HTTP routes tree/list |
| `Services` | Dashboard entries | May be `has no content` until opened once in UI. Result grids are read as CSV, but titles repeat across runs/servers — for console results use `rider_console_results` instead of `section` |
| Others | Varies | Generic extraction: sub-tabs marked `--- <title> ---`, editors as text, trees flattened with indent |

Recipes:
```jsonc
// App stdout of the active debug session, last 50 lines (NOT the debugger trace)
{ "windowId": "Debug", "section": "console", "maxLines": 50, "fromEnd": true }
// Debugger trace tail
{ "windowId": "Debug", "section": "output", "maxLines": 30, "fromEnd": true }
// Finished run output (tab name from rider_tool_window(action='tabs', windowId='Run'))
{ "windowId": "Run", "tab": "e2e tests", "maxLines": 30, "fromEnd": true }
```

### Artifacts diff after Publish

When Publish produces different outputs than the regular build (e.g. Release vs Debug, different RID, trimmed assemblies), compare the two folders directly:

1. Get the relevant paths: `rider_workspace_config` returns `exePath`/`workingDirectory` for the regular build and `publishDir` if a prior Publish was captured in `lastOperation`.
2. Diff directories with shell tools (the agent can read the result):
   - `diff -rq <publishDir> <regularOutputDir>` — which files differ or are unique.
   - `find <publishDir> -type f | sort` vs the regular output to spot missing/extra files.
3. For single-file differences, read both files as binary via `read_file` and compare hashes/bytes, or use `shasum`/`md5sum` on each side.

## Feedback

`feedback/` holds usage feedback. Any agent using these tools should drop a note here after a session with real friction — one file per session/issue, name `YYYY-MM-DD-short-topic.md`. Template: context (1 line) → what helped → what misrouted/missing → proposals by priority.

## Release hygiene

After any significant change (tool code, `McpDescription`, `plugin.xml`, docs that affect evals): run `./gradlew buildPlugin` and report the fresh zip path (`build/distributions/mcp-server-extension-<version>.zip`) plus the tree hash — evals and installs must reference a zip built from the current tree (methodology `notes/evals/methodology.md` p.6).

## Testing rule

New or changed tool behavior ships with tests, no exceptions — pure logic (masking, parsing, pagination caps, validation) as unit tests, IDE-bound paths as IDE tests. A guard without a test is unfinished work: add the test in the same change and report the test run result alongside the zip.
