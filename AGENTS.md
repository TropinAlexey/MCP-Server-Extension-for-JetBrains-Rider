# Rider MCP — Agent Guide

MCP Server extension plugin for JetBrains Rider. Adds `rider_*` tools for full IDE control: build, test, debug, profiling, DB consoles, tool windows, terminals, and more.

Per-tool documentation lives in each tool's description (visible on tool call). This file is the routing layer — which tool for which task.

## Prefer `rider_*` over base MCP Server tools

The base JetBrains MCP Server (`com.intellij.mcpServer`) ships its own database tools. **Use the `rider_*` versions instead** — they handle more DBMS types and edge cases:

| Task | Use this | NOT this |
|---|---|---|
| Create DB connection | `rider_database_connection(action='create')` | `create_database_connection` (broken MSSQL matching, undocumented `dbms` enum) |
| Edit DB connection | `rider_database_connection(action='edit')` | `edit_database_connection` |
| Execute SQL | `rider_execute_console` (via open console) | `execute_sql_query` (works too, but no multi-statement support) |

## Task → Tool Map

- Cursor, selection, open files, bookmarks → `rider_get_context`
- Recently opened files → built-in `get_all_open_file_paths` (open editors) or VCS log for broader history
- IDE readiness (indexing, busy) → `rider_get_ide_state` (call before heavy work)
- IDE errors and warnings → `rider_get_output` (session output) or `rider_tool_window(action='content', windowId='Problems')`
- Solution-wide error count that does not match the Problems panel → `rider_swea_errors`
- Workspace build configuration, phantom errors after Publish, or mismatched RID/platform → `rider_workspace_config`
- Any panel content (Build, Problems, etc.) → `rider_tool_window` (actions: `list`, `tabs`, `content` — see **Pagination** below and **Tool window map**)
- Debugged app stdout (NOT the debugger trace) → `rider_tool_window(action='content', windowId='Debug', section='Console', fromEnd=true)`
- TODO/FIXME/HACK in code → `rider_tool_window(action='content', windowId='TODO')`
- API endpoints → built-in `search_symbol` or `get_service_map`; fallback: `rider_tool_window(action='content', windowId='Endpoints')`
- IDE terminal commands → `rider_list_terminals` + `rider_send_terminal_input`
- Build → `rider_build` (actions: `start`, `cancel`) + `rider_get_output`
- Publish → `rider_publish` (`configName?`) + `rider_get_output`
- Run tests → `rider_tests` (actions: `run`, `run_and_wait`, `results`, `rerun_failed`) + `rider_get_output` (`configName` alone runs the whole config — scope with filters)
- Create/edit DB connections → `rider_database_connection` (actions: `create`, `edit`)
- SQL in open DB consoles → `rider_list_db_consoles` + `rider_execute_console`
- NuGet packages → `rider_nuget` (actions: `list`, `add`, `remove`, `restore`)
- Run/Debug configurations → `rider_run_config` (actions: `create`, `update`, `delete`; type IDs from built-in `get_run_configurations` or IDE Run → Edit Configurations; launches via `rider_start_debug` / `rider_tests`)
- Debugging → `rider_breakpoint` (actions: `set`, `remove`) / `rider_start_debug` / `rider_debug` (actions: `state`, `stepOver`, `stepInto`, `stepOut`, `resume`, `pause`, `stop`, `evaluate`)
- IDE processes → `rider_list_processes` / `rider_kill_process`
- Code inspections → `rider_inspections` (actions: `list`, `toggle`)
- IDE caches → `rider_invalidate_caches`
- Profiling (dotTrace) → `rider_profiling` (actions: `state`, `control`)
- Profiling (dotMemory) → `rider_memory` (actions: `state`, `control`)

## Polling Pattern

`rider_build(action='start')`, `rider_tests(action='run')`, `rider_nuget(action='restore')`, `rider_start_debug`, `rider_tests(action='rerun_failed')` return `sessionId` — poll via `rider_get_output` until `status != "running"`.

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
| `Services` | Dashboard entries | May be `has no content` until opened once in UI |
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
