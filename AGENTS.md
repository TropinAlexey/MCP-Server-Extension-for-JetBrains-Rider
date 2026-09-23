# Rider MCP — Agent Guide

MCP Server extension plugin for JetBrains Rider. Adds `rider_*` tools for full IDE control: build, test, debug, profiling, DB consoles, tool windows, terminals, and more.

Per-tool documentation lives in each tool's description (visible on tool call). This file is the routing layer — which tool for which task.

## Prefer `rider_*` over base MCP Server tools

The base JetBrains MCP Server (`com.intellij.mcpServer`) ships its own database tools. **Use the `rider_*` versions instead** — they handle more DBMS types and edge cases:

| Task | Use this | NOT this |
|---|---|---|
| Create DB connection | `rider_create_database_connection` | `create_database_connection` (broken MSSQL matching, undocumented `dbms` enum) |
| Edit DB connection | `rider_edit_database_connection` | `edit_database_connection` |
| Execute SQL | `rider_execute_console` (via open console) | `execute_sql_query` (works too, but no multi-statement support) |

## Task → Tool Map

- Cursor, selection, open files, bookmarks → `rider_get_context`
- Recently opened files → `rider_get_recent_files`
- IDE readiness (indexing, busy) → `rider_get_ide_state`
- IDE errors and warnings → `rider_get_notifications`
- Any panel content (Build, Problems, etc.) → `rider_list_tool_windows` + `rider_list_tabs` + `rider_get_tool_window_content` (see **Pagination** below and **Tool window map**)
- Debugged app stdout (NOT the debugger trace) → `rider_get_tool_window_content` with `section=console` + `fromEnd=true` (Debug window: `Console` = process stdout via debugger API; `Debug Output` = `Loaded Assembly / Pdb / Started|Exited Thread` trace)
- TODO/FIXME/HACK in code → `rider_get_todos`
- API endpoints → `rider_get_endpoints`
- IDE terminal commands → `rider_list_terminals` + `rider_send_terminal_input`
- Build → `rider_start_build` + `rider_get_output` + `rider_cancel_build`
- Run tests → `rider_run_tests` + `rider_get_output` + `rider_get_test_results` + `rider_rerun_failed_tests` (`rider_run_tests_and_wait` bundles run+wait; `configName` alone runs the whole config — scope with filters)
- Create/edit DB connections → `rider_create_database_connection` (fuzzy DBMS matching, JDBC URL, credentials stored in IDE) / `rider_edit_database_connection` (update URL/name/user/password by connectionId)
- SQL in open DB consoles → `rider_list_db_consoles` (returns `currentDatabase` per console) + `rider_execute_console` (handles multi-statement SQL — each `;`-separated statement gets its own result set; response includes `rowCount`, `pageSize`, `hasMore` per result; three-part names, `database?` pin, slice heavy queries)
- NuGet packages → `rider_list_packages` / `rider_manage_package` / `rider_nuget_restore`
- Run/Debug configurations → `rider_create_run_config` / `rider_update_run_config` / `rider_delete_run_config`
- Debugging → `rider_set_breakpoint` / `rider_remove_breakpoint` / `rider_start_debug` / `rider_debug_state` / `rider_debug_evaluate` / `rider_debug_step`
- IDE processes → `rider_list_processes` / `rider_kill_process`
- Code inspections → `rider_list_inspections` / `rider_toggle_inspection`
- IDE caches → `rider_invalidate_caches`
- Profiling (dotTrace) → `rider_profiling_state` / `rider_profiling_control`
- Profiling (dotMemory) → `rider_memory_state` / `rider_memory_control`

## Polling Pattern

`rider_start_build`, `rider_run_tests`, `rider_nuget_restore`, `rider_start_debug`, `rider_rerun_failed_tests` return `sessionId` — poll via `rider_get_output` until `status != "running"`.

## Pagination & Filtering

`rider_get_tool_window_content` and `rider_get_output` share the same pagination parameters. Response always includes `totalLines` and `returnedRange` so you know exactly what you got and how much more is available.

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
- `availableSections` — sub-tab titles in the window content (`rider_get_tool_window_content` only, always present — use for the `section` param instead of guessing)

**Recipes:**

```jsonc
// Last 20 lines of debug output (90% of use cases)
{ "windowId": "Debug", "maxLines": 20, "fromEnd": true }

// Grep errors/exceptions from entire log
{ "windowId": "Debug", "pattern": "error|exception|warn", "maxLines": 50 }

// Page through output: lines 200-250
{ "windowId": "Debug", "offset": 200, "maxLines": 50 }

// After a build: get all accumulated output, last 30 lines
{ "sessionId": "build_1", "maxLines": 30, "fromEnd": true, "allLines": true }

// Search for a specific class in test output
{ "sessionId": "test_1", "pattern": "MyService", "allLines": true }
```

**Best practices:**
- Always start with `fromEnd: true` for Debug/Build/Console output — useful info is at the bottom
- Use `pattern` to search for errors without downloading the entire log
- Check `totalLines` in the response — if the window has 10K lines, don't request all of them
- For Problems/TODO/tree windows — default (first N lines) is usually fine, no `fromEnd` needed

## Tool Window Map

Model: **window → tabs → Swing component tree**. Navigate: `rider_list_tool_windows` → `rider_list_tabs {windowId}` → `rider_get_tool_window_content {windowId, tab?, section?}`.

| Window | Tabs | Sections inside |
|---|---|---|
| `Debug` | One per debug session (tab = session name) | `Threads & Variables`, `Console` (app stdout, via debugger API), `Debug Output` (assembly/thread trace) |
| `Run` | One per execution (tab = descriptor name) | Process stdout/stderr console |
| `Build` | Build sessions | Compiler output (structured errors also in `Problems`) |
| `Problems` | Scope | Errors/warnings tree |
| `Terminal` | One per terminal | Shell console |
| `TODO` | Scope filter | TODO/FIXME/HACK tree |
| `Endpoints` | — | HTTP routes tree/list |
| `Services` | Dashboard entries | May be `has no content` until opened once in UI |
| Others | Varies | Generic extraction: sub-tabs marked `--- <title> ---`, editors as text, trees flattened with indent |

Recipes:
```jsonc
// App stdout of the active debug session, last 50 lines (NOT the debugger trace)
{ "windowId": "Debug", "section": "console", "maxLines": 50, "fromEnd": true }
// Debugger trace tail
{ "windowId": "Debug", "section": "output", "maxLines": 30, "fromEnd": true }
// Finished run output (tab name from rider_list_tabs("Run"))
{ "windowId": "Run", "tab": "e2e tests", "maxLines": 30, "fromEnd": true }
```

## Feedback

`feedback/` holds usage feedback. Any agent using these tools should drop a note here after a session with real friction — one file per session/issue, name `YYYY-MM-DD-short-topic.md`. Template: context (1 line) → what helped → what misrouted/missing → proposals by priority.
