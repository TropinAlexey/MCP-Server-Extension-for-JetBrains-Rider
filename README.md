<p align="center">
  <img src="icon.png" alt="MCP Server Extension" width="80">
</p>

<h1 align="center">MCP Server Extension for JetBrains Rider</h1>

<p align="center">
  <a href="https://plugins.jetbrains.com/plugin/34264-mcp-server-extension"><img src="https://img.shields.io/jetbrains/plugin/v/34264?style=flat-square&label=marketplace" alt="JetBrains Marketplace"></a>
  <a href="https://plugins.jetbrains.com/plugin/34264-mcp-server-extension"><img src="https://img.shields.io/jetbrains/plugin/d/34264?style=flat-square&label=downloads" alt="Downloads"></a>
  <a href="https://github.com/TropinAlexey/MCP-Server-Extension-for-JetBrains-Rider/releases/latest"><img src="https://img.shields.io/github/v/release/TropinAlexey/MCP-Server-Extension-for-JetBrains-Rider?style=flat-square&label=version" alt="Version"></a>
  <a href="https://github.com/TropinAlexey/MCP-Server-Extension-for-JetBrains-Rider/actions/workflows/build.yml"><img src="https://img.shields.io/github/actions/workflow/status/TropinAlexey/MCP-Server-Extension-for-JetBrains-Rider/build.yml?style=flat-square" alt="Build"></a>
  <img src="https://img.shields.io/badge/Rider-2026.1%2B-blue?style=flat-square&logo=jetbrains" alt="Rider 2026.1+">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-yellow?style=flat-square" alt="MIT License"></a>
</p>

<p align="center">
  A JetBrains Rider plugin that extends the built-in <a href="https://www.jetbrains.com/help/idea/mcp-server.html">MCP Server</a> with full IDE observability and control tools for AI agents.
</p>

[What's New →](#whats-new)

## Why This Exists

The built-in MCP server covers files, search, and running things, but most of what it exposes either duplicates what MCP clients already do better natively (file reading/editing, git operations) or misses the IDE surfaces that matter day to day.

**The core problem: your AI assistant is blind to what happens inside the IDE.**

A programmer staring at Rider sees dozens of information surfaces simultaneously — Build Output streaming in real time, Problems panel lighting up with errors, test runner trees expanding, progress bars showing indexing status, notifications popping up about package restores. An MCP client sees none of this.

When a build hangs, you see the output frozen at "Building Project7..." — your assistant sees nothing. When publish fails silently, you see a red notification balloon — your assistant sees nothing. When zombie MSBuild nodes eat CPU, you see Activity Monitor — your assistant sees nothing.

This plugin bridges that gap. It gives any MCP-compatible client (Claude Code, Cursor, Windsurf, Continue, custom agents) the same situational awareness a programmer has — the ability to see what the IDE is doing, what it's showing, and what's happening in the background.

### What the stock MCP plugin provides vs. what this adds

| Capability | Built-in MCP Server | This Extension |
|---|---|---|
| Read/edit files | ✅ (most clients do it natively) | — |
| Build solution | ✅ start + final status | ✅ streaming output via sessionId + `rider_get_output`, cancel, progress |
| See build errors | ✅ after build completes | ✅ real-time via Problems panel (`rider_tool_window`) + deep text extraction |
| Process management | ❌ | ✅ list & kill IDE-managed processes |
| IDE state/progress | ❌ | ✅ indexing, busy, runningSessions |
| Tool windows | ❌ | ✅ read any tool window via `rider_tool_window(action=list/tabs/content)` |
| Programmer context | Partial (open files, selection) | ✅ all-in-one: cursor, selection, surrounding code, open editors, bookmarks, unsaved files |
| Test runner | Partial (run via configs) | ✅ `rider_tests(action=run/run_and_wait/results/rerun_failed)` with stack traces |
| Run config CRUD | Partial (list & execute, built-in) | ✅ `rider_run_config(action=create/update/delete)` |
| .NET debugger | Partial (xdebug only) | ✅ `rider_breakpoint` + `rider_start_debug` + `rider_debug(state/step/evaluate)` |
| NuGet management | ❌ | ✅ `rider_nuget(action=list/add/remove/restore)` |
| IDE settings | ❌ | ✅ `rider_inspections(action=list/toggle)` |
| Terminal integration | Partial (execute commands) | ✅ list tabs, send input to specific terminals |
| TODO / endpoints | ❌ | ✅ via `rider_tool_window` (TODO / Endpoints panels) |
| DB consoles & SQL | Partial (built-in single-statement) | ✅ `rider_list_db_consoles` + multi-statement `rider_execute_console` with background jobs, read-only guard, secret masking, CSV/JSON export; `rider_console_results` for user-run results |
| Cache invalidation | ❌ | ✅ invalidate caches & restart |
| dotTrace / dotMemory | Partial (report analysis) | ✅ live session state + control |

## How It Works

This is a companion to the built-in MCP server — not a replacement and not a fork. Install it, and your MCP client sees all of their tools as one unified set. Everything this plugin adds is prefixed with `rider_`, so there are no naming conflicts.

### Polling Pattern for Async Operations
MCP tools are synchronous (request → response). Long-running operations — builds, test runs, package restores, debug sessions — use a polling pattern instead of blocking:

1. `rider_build(action='start')` / `rider_tests(action='run')` / `rider_nuget(action='restore')` / `rider_start_debug` → returns `{"sessionId": "build_1"}`
2. `rider_get_output("build_1")` → returns new lines since the last call, plus current status
3. Repeat until `status` is no longer `"running"` (`allLines=true` re-reads full history; `rider_tests(action='results', sessionId=...)` gives the structured test tree)

## Available Tools (25)

### Build & shared polling
| Tool | Args | Description |
|---|---|---|
| `rider_build` | `action=start\|cancel` | Build solution (start returns sessionId) / cancel running build. Poll with `rider_get_output`: compile errors/warnings arrive as `error CODE: file(line,col): message` lines, then `Build failed`/`succeeded` |
| `rider_get_output` | `sessionId`, `maxLines?`, `fromEnd?`, `pattern?`, `offset?`, `allLines?` | Poll any async session (build, test, restore, debug). Delta by default, `allLines=true` for full history; `fromEnd` = tail, `pattern` = regex grep, `offset` = paging |

### Solution analysis & workspace config
| Tool | Args | Description |
|---|---|---|
| `rider_swea_errors` | `maxExamples?=50` | Solution Wide Analysis error/warning counts per project, top error codes, and sample issues. Use when the solution-wide error count does not match the Problems panel |
| `rider_workspace_config` | — | Read-only snapshot of runnable-project outputs (Configuration, Platform, TargetFramework, exePath, workingDirectory) and the last `rider_*` build/publish/test operation metadata. Use to diagnose phantom errors after Publish or mismatched RIDs |

### Publish
| Tool | Args | Description |
|---|---|---|
| `rider_publish` | `configName?` | Trigger a Publish run configuration and return a sessionId. Captures Configuration/Platform/PublishProfile metadata for `rider_workspace_config` |

### Test runner
| Tool | Args | Description |
|---|---|---|
| `rider_tests` | `action=run\|run_and_wait\|results\|rerun_failed`, `configName?`, `filter?`, `className?`, `methodName?`, `sessionId?`, `timeoutMs?` | Run scoped tests (prefer `filter`/`className`/`methodName` — bare `configName` runs the whole config). `run` → sessionId + poll; `run_and_wait` → blocks; `results` → structured tree (IDE config runs only); `rerun_failed` → retry failures |

### Process management
| Tool | Args | Description |
|---|---|---|
| `rider_list_processes` | `type?` (build/test/run) | Live IDE-managed processes with PID, command line, display name |
| `rider_kill_process` | `processName` (exact, from list) | Force-kill a hung process; prefer native stop (`rider_build cancel`, `rider_debug stop`) when applicable |

### IDE state & tool windows
| Tool | Args | Description |
|---|---|---|
| `rider_get_ide_state` | — | Readiness: activeFile, indexing, busy, runningSessions. Check before heavy work |
| `rider_tool_window` | `action=content\|list\|tabs`, `windowId?`, `all?`, `tab?`, `section?`, `maxLines?`, `offset?`, `fromEnd?`, `pattern?` | Read any panel (Build, Run, Debug, Problems, TODO, Terminal, Endpoints, ...). Navigate list → tabs → content; `section='Console'` = app stdout, `section='Debug Output'` = debugger trace |

### Run configurations
| Tool | Args | Description |
|---|---|---|
| `rider_run_config` | `action=create\|update\|delete`, `name`, `copyFrom?`, `typeId?`, `programArgs?`, `env?`, `newName?` | CRUD for run/debug configs (does not launch; launch via built-in `execute_run_configuration`, `rider_start_debug`, `rider_tests`). `create` clones `copyFrom` (keeps the project) or makes an empty `typeId` config; args/env work for .NET project/exe configs |

### .NET debugger
| Tool | Args | Description |
|---|---|---|
| `rider_breakpoint` | `action=set\|remove`, `filePath`, `line` | Arm/disarm line breakpoint (1-indexed) before debugging |
| `rider_start_debug` | `configName?` | Launch app under debugger, returns sessionId |
| `rider_debug` | `action=state\|stepOver\|stepInto\|stepOut\|resume\|pause\|stop\|evaluate`, `expression?` | Inspect state/stack, step, evaluate (must be paused). App stdout via `rider_get_output` or Debug Console section |

### NuGet
| Tool | Args | Description |
|---|---|---|
| `rider_nuget` | `action=list\|add\|remove\|restore`, `name?`, `projectPath?`, `version?`, `outdated?` | list/add/remove packages; restore returns sessionId for polling |

### Inspections
| Tool | Args | Description |
|---|---|---|
| `rider_inspections` | `action=list\|toggle`, `filter?`, `enabledOnly?`, `limit?`, `shortName?`, `enabled?` | Catalog of static-analysis rules (not the Problems panel) |

### Terminal
| Tool | Args | Description |
|---|---|---|
| `rider_list_terminals` | — | IDE terminal tabs (index + name) |
| `rider_send_terminal_input` | `text`, `tab?=0` | Execute shell input in a terminal tab (not for run/debug consoles) |

### Database
| Tool | Args | Description |
|---|---|---|
| `rider_database_connection` | `action=create\|edit\|open_console\|open_table\|refresh`, `dbms?`, `url?`, `name?`, `user?`, `password?`, `connectionId?`, `readOnly?`, `table?` | Manage data sources (JDBC), mark them Read-only, open the default console or a table tab, re-introspect the model. Returns uniqueId. Prefer over built-in create/edit (MSSQL matching fixed) |
| `rider_list_db_consoles` | — | Open SQL consoles with data source, currentDatabase, readOnly, busyJobId; `tableEditors` = open table/view tabs with database/schema/object. Call first |
| `rider_execute_console` | `sql`, `console?`, `pageSize?`, `database?`, `offset?`, `timeoutSeconds?=60`, `maxCellLength?=4000`, `split?=auto`, `async?`, `waitSeconds?=50`, `onClientTimeout?=cancel\|detach`, `readOnly?`, `maskSecrets?=true`, `outputFile?`, `inIde?` | Execute SQL on a dedicated JDBC connection → CSV per result set with rowCount/hasMore; server error text + code + SQLState; T-SQL split by `GO`; per-batch timeout with cancel; background jobs; read-only guard (forced for Read-only data sources); secret masking; CSV/JSON export; `inIde=true` runs it in the console itself (result tabs in the IDE). Prefer over built-in execute |
| `rider_console_job` | `action=status\|result\|cancel\|list`, `jobId?`, `messagesOffset?`, `waitSeconds?` (long-poll ≤40 s) | Poll/cancel `rider_execute_console` jobs: status, timestamped PRINT/RAISERROR messages, final result |
| `rider_console_results` | `console?`, `index?`, `offset?`, `pageSize?`, `maxCellLength?`, `outputLines?`, `maskSecrets?`, `outputFile?` | Result grids + Output log of what the user ran in a console (Services → Database); `state=running` while executing |

### Profiling
| Tool | Args | Description |
|---|---|---|
| `rider_profiling` | `action=state\|control`, `command?=start\|stop\|drop\|detach\|close`, `pid?` | Live dotTrace session (start it from Rider Run → Profile first) |
| `rider_memory` | `action=state\|control`, `command?=snapshot\|open\|detach\|kill`, `pid?`, `path?` | Live dotMemory session (snapshot needs pid, open needs .dmw path) |

### Admin & context
| Tool | Args | Description |
|---|---|---|
| `rider_invalidate_caches` | — | Nuclear option: invalidate caches + restart IDE (sessions die) |
| `rider_get_context` | — | Programmer focus: active file + cursor + selection + ±5 lines + open editors + bookmarks |

### Tool Window Map

Every tool window follows the same model: **window → tabs → sections**.
Navigate in three steps: `rider_tool_window(action='list')` → `rider_tool_window(action='tabs', windowId=...)` → `rider_tool_window(action='content', ...)`.

| Window | Tabs | Inside each tab |
|---|---|---|
| `Debug` | One tab per debug session (tab = session name, e.g. `LAPICore.Api`) | `Threads & Variables` tree, `Console` (debugged app stdout), `Debug Output` (debugger trace: `Loaded Assembly`, `Pdb file`, `Started/Exited Thread`) |
| `Run` | One tab per active execution (tab = descriptor name, e.g. `e2e tests`) | Process stdout/stderr console |
| `Build` | Build sessions | Compiler output; structured errors are also in `Problems` |
| `Problems` | Current file / project scope | Errors and warnings tree |
| `Terminal` | One tab per terminal | Shell console (read via content, write via `rider_send_terminal_input`) |
| `TODO` | Scope filter | TODO/FIXME/HACK tree |
| `Endpoints` | — | HTTP routes tree/list |
| `Services` | Run dashboard entries | Service/run consoles. May report `has no content` until opened once in Rider (`View → Tool Windows → Services`) |
| `NuGet`, `Database`, others | Varies | Generic text/tree extraction (see rules below) |

How content is returned (`rider_tool_window(action='content', ...)`):
- Every response lists `availableSections` — the sub-tabs found inside — so there is no need to guess their names.
- `section` reads a single sub-tab by a name fragment (case-insensitive), e.g. `section=console`.
- The Debug window exposes two dedicated sections: `Console` (your application's own output) and `Debug Output` (the debugger trace — loaded assemblies, thread events).
- Trees and lists are flattened into indented plain text; long outputs can be paged with `maxLines`, `fromEnd`, `pattern`, and `offset`.
- If a section name is not found, the error lists the sections that actually exist.
- A window that has never been opened in Rider (e.g. Services) reports that, with a hint to open it first via View → Tool Windows.

Recipes:
```jsonc
// Application output of the active debug session, last 50 lines (not the debugger trace)
{ "action": "content", "windowId": "Debug", "section": "console", "maxLines": 50, "fromEnd": true }

// Debugger trace tail (assemblies, threads)
{ "action": "content", "windowId": "Debug", "section": "output", "maxLines": 30, "fromEnd": true }

// Process output of a finished run (tab name from tabs action on "Run")
{ "action": "content", "windowId": "Run", "tab": "e2e tests", "maxLines": 30, "fromEnd": true }
```

## Installation

### Prerequisites
- JetBrains Rider 2026.1+ (the MCP server is built in — no separate plugin to install)
- An MCP-compatible client: Claude Code, Cursor, Windsurf, Continue, or a custom agent

### From Marketplace

1. In Rider: **Settings → Plugins → Marketplace**, search for "MCP Server Extension", install, and restart.
2. Enable the built-in MCP server: **Settings → Tools → MCP Server → Enable MCP Server** (confirm the access dialog), then Apply. If the settings page is missing, check that the bundled MCP Server plugin is enabled under **Settings → Plugins → Installed**.
3. Connect your client: on the same settings page use **Auto-Configure** next to your client (or copy a manual config for clients not on the list), then restart the client.
4. Verify: ask your agent something only the IDE knows — e.g. "What is the current IDE state?" or "What does the Problems panel show?". If it answers with live IDE data, the `rider_*` tools are working.

### Quick Start

Once connected, try:
- "Build the solution and show me the errors" — the agent streams the build and reads the Problems panel
- "What was the last notification in the IDE?" — balloon messages and event log
- "Run the tests for `MyServiceTests` and summarize the failures" — test run with structured results
- "Show me the TODOs in this project" / "List the API endpoints" — project insights in one call

### From Source

```bash
git clone https://github.com/TropinAlexey/MCP-Server-Extension-for-JetBrains-Rider.git
cd MCP-Server-Extension-for-JetBrains-Rider

# Linux / macOS
./gradlew buildPlugin

# Windows
gradlew.bat buildPlugin
```

Then install: **Rider → Settings → Plugins → ⚙️ → Install Plugin from Disk → select `build/distributions/mcp-server-extension-*.zip`**

### Development

```bash
# Run Rider with the plugin loaded (Linux/macOS)
./gradlew runIde

# Windows
gradlew.bat runIde
```

## Troubleshooting

| Symptom | What to do |
|---|---|
| Agent says a tool window "has no content/tabs" | Open that window once in Rider (**View → Tool Windows → …**) — unopened windows have nothing to read |
| "No endpoints found..." | Check `rider_get_ide_state` for indexing/build progress and retry when idle; if idle and still empty, verify in Rider via View → Tool Windows → Endpoints (panel has no detected routes). Fallback: text search for route attributes |
| "No test configurations found" | Create a test run configuration (Run → Edit Configurations → +), or bypass configs by passing `filter`/`className`/`methodName` to run `dotnet test --filter` directly |
| "Multiple test configs found" | Pass `configName` to pick one |
| "No active debug session" | Start one with `rider_start_debug` |
| "Debugger is not paused..." | Pause first — evaluating/stepping require a paused debugger (pausing freezes the live process; prefer a dev/test instance) |
| Debug console is empty | The session produced no application output (the debugger trace is still in `Debug Output`) |
| Terminal "not ready" | The tab has no attached process yet — open a terminal in Rider and retry |
| "Configuration '…' not found" | Names must match exactly — list them with the built-in `get_run_configurations` (or IDE Run → Edit Configurations) |
| `rider_list_db_consoles` returns `[]` | Open the console in an editor tab first (double-click it in the Database tool window) |
| A tool returns a file path you cannot read | Read only paths from `rider_get_context` (project-relative, always readable); absolute local path as fallback. Never feed one tool's path into another tool blindly |
| Target database missing from the schema list | The list is the introspected subset, not the truth. Run the query through any introspected database with three-part names (`db.schema.table`) and confirm via `SELECT name FROM sys.databases` |
| Heavy SQL (`COUNT LIKE` over `CAST`, full-table scans) | Confirm the database context first, then slice: `TOP`, date/id ranges, `EXISTS` instead of `COUNT LIKE`, `pageSize` for paging. Deliberate cancel is normal practice, not a failure |
| Tempted by generic `execute_tool` | Don't — it has no action listing and can't reach DB consoles or run profiles. Use the `rider_*` tool for the job |

## Known limits (accepted, not hidden)

- Tool-window reads run on the EDT and are capped at 20000 lines with a truncation marker — huge consoles don't freeze the IDE, but page with `section`/`pattern`/`maxLines` instead of dumping.
- `rider_send_terminal_input` is fire-and-forget: terminal scrollback has no reliable read-back via tools. Need output? Use `rider_build` / `rider_tests` / `rider_nuget`.
- `rider_kill_process` matches by exact display name; ambiguous names fail — pass `pid` from the same listing.
- `rider_debug(action='evaluate')` runs code inside the debuggee (getters can have side effects) — dev/test instances only.
- `OFFSET` paging without `ORDER BY` is nondeterministic — always pair them.
- MCP calls longer than ~60 s are dropped by the MCP server (HTTP 404): tools that wait cap themselves at 50 s (`waitSeconds`) or 40 s (long-poll, `open_table`, `inIde`). Longer work → `async=true` + `rider_console_job`.
- `rider_execute_console(inIde=true)` is refused on Read-only data sources and with `readOnly=true`: the IDE console run has no rolled-back transaction or read-only session.
- Agents still sometimes pick the built-in `execute_sql_query`, which waits for an IDE confirmation dialog. To rule it out, hide it under **Settings → Tools → MCP Server → Exposed Tools**.
- Eval rounds R8–R10 (DB consoles, S4/S12–S21) run automatically in an isolated sandbox Rider against a docker fixture — never against real servers. Ship-gate per `CONTRACT.md`.

## Limitations & Privacy

- Rider must be running with a project open — the tools observe and control the current project.
- Enabling the MCP server grants external applications access to your open projects (you confirm this in the dialog at enable time). Review which tools are exposed under **Settings → Tools → MCP Server → Exposed Tools**.
- What the tools can see: file contents, cursor and selection, open editors, tool window text (including consoles and outputs), notifications, database console bindings and query results. What they never do: nothing leaves your machine through this plugin — it only serves the local MCP server; network calls happen only where you ask (NuGet restore, package checks).
- What the tools can change: sending terminal input, killing processes, editing run configs, toggling inspections, executing SQL, profiling sessions, restarting the IDE after cache invalidation. Read-only tools (state, content, lists, context) never change anything.
- Running commands without confirmation is opt-in ("brave mode" in the MCP server settings) and stays off by default — keep it off unless you trust the agent. This is a security feature, not an inconvenience.

## What's New

### v1.2.11

- `rider_run_config` can create a working .NET app configuration with arguments and environment variables: `action='create'` with the new `copyFrom` clones an existing configuration (project, framework, working directory) and applies `programArgs`/`env` — these used to be rejected for .NET project configs
- An unknown `typeId` now lists the valid type IDs; a .NET project config created without a project carries a warning

### v1.2.10

- `rider_build` no longer reports `Build succeeded` for a broken solution: the status now comes from Rider's own build result, and compile errors/warnings are listed in the session output as `error CS0029: src/A.cs(6,34): message`
- `rider_tool_window(windowId='Debug', section='Console')` reads the app's stdout when Rider shows it in a terminal-style console (it used to come back empty); `availableSections` no longer repeats entries

### v1.2.9

- `rider_database_connection` `open_console` no longer uses the `FileEditorManager` API deprecated in Rider 2026.3 (263)

### v1.2.8

- Compatibility with Rider 2026.3 (263): data source refresh in `rider_database_connection` (`refresh`, `open_table`) no longer uses the `DataSourceSyncManager` API removed in 263; `open_console` drops the deprecated `FileEditorManager.openFile`

### v1.2.7

- Security: `outputFile` exports no longer follow symbolic links — a dangling link inside the project could redirect the write outside the allowed directories. Links are rejected, and files are created atomically when `overwrite` is off

### v1.2.6

- `outputFile` accepts `.txt` (one line per row, no header — a ready list for scripts) in `rider_execute_console` and `rider_console_results`. Exports are limited to the project, temp/scratchpad dirs and `-Drider.mcp.exportDirs`; an existing file is replaced only with `overwrite=true`
- SQL errors carry severity and line (MSSQL); failed batches of multi-batch runs get `errorCode`, `sqlState`, `severity`, `line`, `message`
- Responses list `columns` and the session's real `database` (`DB_NAME()`); `rider_list_db_consoles` takes the login-default database from the model, `currentDatabaseSource` is `toolbar|url|login-default`

### v1.2.5

- Security: `rider_execute_console(inIde=true)` is refused on Read-only data sources and with `readOnly=true` — the IDE console run cannot roll changes back, so read-only rested on the lexical guard alone. The flag is re-checked at run time
- `inIde` runs on the console resolved when the call was checked, not on whichever tab is selected later

### v1.2.4

- `rider_execute_console(inIde=true)` runs the script in the console itself, like the user's Execute: result tabs and Output appear in the IDE, the console's session (`#temp` tables, `SET` options) is reused; read them with `rider_console_results`. The read-only guard applies; replacing the console text is undoable
- Eval S15 (results of a user-run script) no longer needs a manual step

### v1.2.3

- `rider_console_job(action='status', waitSeconds=N)` long-polls (≤40 s) until the job ends or new server messages arrive — eval S19: 32 → 6 calls for a 75 s script
- `rider_console_results`: `output` holds only the Output log (the script editor's text no longer leaks in)
- `rider_list_db_consoles` steers SQL to `rider_execute_console(console=consoleRef)` instead of the built-in `execute_sql_query`, which blocks on an IDE confirmation dialog

### v1.2.2

- `rider_database_connection(action='open_table', table='db.schema.table')` opens a table/view data tab (like a double-click), refreshing the model first when the object is not introspected yet
- `rider_database_connection(action='refresh')` re-introspects a data source — the fix for built-in model tools answering "Schema … is not found"
- `rider_list_db_consoles` → `tableEditors[]` entries carry `query`: how to query the tab (`rider_execute_console` on the same data source's console, or how to open one)

### v1.2.1

- Fix: the plugin did not load on Rider 2026.1 — the test runner dependency (a separate plugin only since 2026.2) is now optional
- Consoles are addressed as `<data source>/<file>` (`consoleRef` in `rider_list_db_consoles`): default consoles of different servers are all named `console.sql`, and a bare ambiguous name is now rejected instead of silently running on the first matching tab — possibly another server
- `rider_database_connection`: `readOnly=true` marks a data source Read-only (cannot be lifted through MCP — only the user can, in Rider); `action=open_console` opens the default console and returns its `consoleRef`

### v1.2.0

- Long queries no longer leave orphaned server requests:
  - `rider_execute_console(async=true)` returns a `jobId` at once; new `rider_console_job(action=status|result|cancel|list)` polls status, current batch and timestamped `PRINT`/`RAISERROR … WITH NOWAIT` messages, returns the result or cancels the statement on the server (force-closing the connection if the driver ignores the cancel)
  - a sync call waits `waitSeconds` (default 50, below MCP client timeouts), then cancels the server query (`onClientTimeout=cancel`) or keeps it as a job (`detach`)
  - a busy console answers immediately with its `jobId` instead of queueing
- Read-only mode: `readOnly=true`, forced for data sources marked Read-only in Rider. DML/DDL, `COMMIT`, non-system procedures and dynamic SQL built in variables are rejected before sending; MSSQL runs in a rolled-back transaction, PostgreSQL in a read-only session; `#temp` tables, table variables and `CREATE TEMP TABLE` stay allowed. Responses report `readOnlyEnforced`
- `maskSecrets` (default on): password/secret/token/api_key columns become `***`, `password=` values inside connection strings are masked
- `outputFile`: every result set is streamed to CSV/JSON; the response keeps path, row count and a 5-row preview
- New `rider_console_results`: result grids (CSV with columns, loaded/total rows) and the Output log of scripts the user ran in a console; `state=running` while executing, never blocks the console
- `rider_list_db_consoles`: `readOnly`, `busyJobId`, `selected`, `currentDatabase` falls back to the URL's database, and `tableEditors` lists open table/view tabs with data source, database, schema and object
- `rider_tool_window`: result grids are returned as CSV, tree nodes use their rendered names (no more `Class@hash` noise in Services)

### v1.1.5

- `rider_execute_console` runs on a dedicated JDBC connection of the console's data source instead of the IDE AI query processor:
  - errors carry the real server text, vendor code and SQLState (e.g. `Invalid column name 'value'. [error 207, SQLState S0001]`) instead of «Unknown error» / «no details from IDE»; `PRINT`/`RAISERROR` output returned in `messages`
  - the console's `currentDatabase` is used when `database` is omitted (no more «No database selected» with a database picked in the toolbar)
  - T-SQL (MSSQL/Sybase) is split by `GO` lines, not by `;` — `DECLARE @sql …; select @sql = …; exec sp_executesql @sql` runs as one batch; every result set and update count of a batch is returned; `split=go|semicolon|none` overrides
  - `timeoutSeconds` (default 60, 0 = none): driver query timeout plus a watchdog that cancels the statement; the timed-out batch is named, later batches are reported as skipped, and the connection is discarded so the next call starts clean. Client cancellation cancels the running statement too
  - `maxCellLength` (default 4000) cuts long cells with a `…[+N chars]` marker and reports `truncatedCells`; `offset` pages through big result sets
- Driver error texts are passed through JDBC secret masking; `password=` masking is now case-insensitive (`Password=` in MSSQL URLs)
- `rider_list_db_consoles`: `currentDatabase` is resolved from the search path's database node (no schema names posing as databases), plus `currentSchema`

### v1.1.4

- New `rider_swea_errors`: exposes Solution Wide Analysis (SWEA) error/warning counts per project, top codes, and sample issues — the source of the status-bar solution-wide error count
- New `rider_workspace_config`: read-only snapshot of runnable-project outputs (Configuration, Platform, TargetFramework, exePath, workingDirectory) plus last `rider_*` build/publish/test operation metadata; use it to diagnose phantom errors after Publish or mismatched RIDs
- New `rider_publish`: trigger an existing Publish run configuration, stream its output, and capture Configuration/Platform/PublishProfile metadata
- `rider_get_context` and `rider_get_ide_state` now include `projectDir` (absolute path to the project root)
- Session metadata: `rider_build`, `rider_tests`, and `rider_publish` record active `configuration`/`platform` (plus test `configName` and publish profile/pubxml when known)
- AGENTS.md recipe for diffing Publish artifacts against regular build outputs

### v1.1.3

- Production-grade `McpDescription` for all 22 `rider_*` tools: real action names only, when-to-use routing, recipes — no more dead references to pre-merge tool names
- Safety guards: JDBC credentials (password + userinfo) masked in responses, tool-window extraction capped, overlong regex patterns matched literally, `rider_kill_process` by pid with ambiguity refusal, `.dmw` validation on memory open, quote-aware env parsing
- 18 unit tests (`src/test`) + `checkToolDocs` build check (README sync, description budget) wired into `check`
- Eval scenarios updated to action-based chains; round R7 registered in `notes/evals/runs.md`

### v1.1.2

- `rider_execute_console`: returns all result sets for multi-statement SQL (semicolon-aware splitter), adds `rowCount`/`hasMore`/`pageSize` per result set — no more silent data loss or blind truncation
- `rider_list_db_consoles`: now includes `currentDatabase` per console
- Improved SQL error messages: actionable hints instead of bare "Unknown error"
- Debug/test sessions fail fast on `processNotStarted` instead of hanging forever
- Agent hints extracted from CLAUDE.md to [AGENTS.md](AGENTS.md) for cross-agent use (Copilot, Cursor, Windsurf, etc.)

### v1.1.1

- `rider_run_tests` / `rider_run_tests_and_wait`: hardened descriptions steer a vague «run tests» prompt to a scoped run instead of silently running the whole solution (eval-proven, see `notes/evals/results-log.md` R6g)
- Compat: current runner and snapshot APIs
- Docs: actualized roadmap, README and agent guide

### v1.1.0

- New `rider_execute_console`: run SQL in an open console's data source without IDE introspection
- New `rider_memory_state` / `rider_memory_control`: live dotMemory sessions — status, snapshots, detach/kill (snapshot analysis stays with the stock tools)
- Config-less test runs: `filter`/`className`/`methodName` resolve the `.sln` automatically (no more MSB1011); non-runnable profiles (Publish/MSBuild) fail fast instead of hanging
- `rider_get_ide_state` reports `indexing`/`busy`/`runningSessions` — check before piling onto a busy IDE
- Honest, actionable error texts across debug, tests, endpoints and DB tools

### v1.0.6

- Long outputs can be read in pages — tool windows and build/test sessions: jump to the last lines, search with a pattern, or navigate page by page
- Read a single sub-tab of any tool window with the new `section` parameter — e.g. just the application output, without the surrounding debugger noise
- Debug window now clearly separates two views: `Console` (your application's own output) and `Debug Output` (the debugger trace)
- New `rider_list_tabs` tool: see tab names and the selected tab of any tool window (38 tools in total)
- New `rider_list_db_consoles`: open DB consoles with their data sources — the agent finds the right database in one call
- New `rider_run_tests_and_wait`: run tests and wait in a single call instead of manual polling
- More reliable reading of tool windows and IDE panels: no more freezes or silently missed content
- Heavy tools (build, tests, restore) point at `rider_get_ide_state` first, so the agent doesn't pile onto a busy IDE

### v1.0.1

- Debug console and Run output sub-panels are now visible to the agent; console text reads correctly

### v1.0.0

- First stable release: 35 tools, Rider 2026.1+

<details>
<summary>Older releases</summary>

### v0.2.2

- Requires Rider 2026.1+

### v0.2.1

- Slimmed down to 35 tools; verified compatible with Rider 2026.2

### v0.2.0

- Build infrastructure for Rider 2026 compatibility

### v0.11.0 — dotTrace Profiling
- `rider_profiling_state` / `rider_profiling_control` — dotTrace session management

### v0.10.0 — Terminal & Insights
- `rider_list_terminals` / `rider_send_terminal_input`, `rider_get_todos`, `rider_get_endpoints`

### v0.9.0 — API Compatibility
- Renamed to "MCP Server Extension", published to Marketplace

### v0.8.0 — NuGet & Inspections
- `rider_list_packages` / `rider_manage_package` / `rider_nuget_restore`, `rider_list_inspections` / `rider_toggle_inspection`

### v0.7.0 — .NET Debugger
- `rider_set_breakpoint` / `rider_remove_breakpoint` / `rider_start_debug` / `rider_debug_state` / `rider_debug_evaluate` / `rider_debug_step`

### v0.6.0 — Test Filtering
- `rider_run_tests` with `className`/`methodName`/`filter`, process type filtering

### v0.5.0 — Test Results Tree
- `rider_get_test_results` — structured tree with stack traces

### v0.4.0 — Run Config CRUD
- `rider_create_run_config` / `rider_update_run_config` / `rider_delete_run_config`

### v0.3.0 — Tool Window Content
- Deep text extraction from any tool window

### v0.2.0 — Test Runner & Token Optimization
- `rider_run_tests` / `rider_rerun_failed_tests`; five context tools merged into one `rider_get_context`

### v0.1.0
- Initial release — 15 tools

</details>

## Contributing

Bug reports and feature requests are welcome in [GitHub Issues](https://github.com/TropinAlexey/MCP-Server-Extension-for-JetBrains-Rider/issues).

Tool response compatibility is covered by [CONTRACT.md](CONTRACT.md): response fields are additive-only.

## License

MIT
