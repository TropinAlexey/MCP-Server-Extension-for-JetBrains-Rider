<p align="center">
  <img src="icon.png" alt="MCP Server Extension" width="80">
</p>

<h1 align="center">MCP Server Extension for JetBrains Rider</h1>

<p align="center">
  <a href="https://plugins.jetbrains.com/plugin/34264-mcp-server-extension"><img src="https://img.shields.io/jetbrains/plugin/v/34264?style=flat-square&label=marketplace" alt="JetBrains Marketplace"></a>
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
| DB consoles & SQL | Partial (built-in single-statement) | ✅ `rider_list_db_consoles` + multi-statement `rider_execute_console` |
| Cache invalidation | ❌ | ✅ invalidate caches & restart |
| dotTrace / dotMemory | Partial (report analysis) | ✅ live session state + control |

## How It Works

This is a companion to the built-in MCP server — not a replacement and not a fork. Install it, and your MCP client sees all of their tools as one unified set. Everything this plugin adds is prefixed with `rider_`, so there are no naming conflicts.

### Polling Pattern for Async Operations
MCP tools are synchronous (request → response). Long-running operations — builds, test runs, package restores, debug sessions — use a polling pattern instead of blocking:

1. `rider_build(action='start')` / `rider_tests(action='run')` / `rider_nuget(action='restore')` / `rider_start_debug` → returns `{"sessionId": "build_1"}`
2. `rider_get_output("build_1")` → returns new lines since the last call, plus current status
3. Repeat until `status` is no longer `"running"` (`allLines=true` re-reads full history; `rider_tests(action='results', sessionId=...)` gives the structured test tree)

## Available Tools (22)

### Build & shared polling
| Tool | Args | Description |
|---|---|---|
| `rider_build` | `action=start\|cancel` | Build solution (start returns sessionId) / cancel running build. Poll with `rider_get_output`; structured errors also in Problems panel |
| `rider_get_output` | `sessionId`, `maxLines?`, `fromEnd?`, `pattern?`, `offset?`, `allLines?` | Poll any async session (build, test, restore, debug). Delta by default, `allLines=true` for full history; `fromEnd` = tail, `pattern` = regex grep, `offset` = paging |

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
| `rider_run_config` | `action=create\|update\|delete`, `name`, `typeId?`, `programArgs?`, `env?`, `newName?` | CRUD for run/debug configs (does not launch; launch via `rider_start_debug`/`rider_tests`). `typeId` from built-in `get_run_configurations` (or IDE Run → Edit Configurations) |

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
| `rider_database_connection` | `action=create\|edit`, `dbms?`, `url?`, `name?`, `user?`, `password?`, `connectionId?` | Manage data sources (JDBC). Returns uniqueId. Prefer over built-in create/edit (MSSQL matching fixed) |
| `rider_list_db_consoles` | — | Open SQL consoles with data source + currentDatabase. Call first |
| `rider_execute_console` | `sql`, `console?`, `pageSize?`, `database?` | Execute SQL (multi-statement supported) → CSV with rowCount/hasMore. Prefer over built-in execute (no introspection needed) |

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
- Post-description-rewrite eval round (R7) is pending — see `notes/evals/runs.md`. Ship-gate per `CONTRACT.md`.

## Limitations & Privacy

- Rider must be running with a project open — the tools observe and control the current project.
- Enabling the MCP server grants external applications access to your open projects (you confirm this in the dialog at enable time). Review which tools are exposed under **Settings → Tools → MCP Server → Exposed Tools**.
- What the tools can see: file contents, cursor and selection, open editors, tool window text (including consoles and outputs), notifications, database console bindings and query results. What they never do: nothing leaves your machine through this plugin — it only serves the local MCP server; network calls happen only where you ask (NuGet restore, package checks).
- What the tools can change: sending terminal input, killing processes, editing run configs, toggling inspections, executing SQL, profiling sessions, restarting the IDE after cache invalidation. Read-only tools (state, content, lists, context) never change anything.
- Running commands without confirmation is opt-in ("brave mode" in the MCP server settings) and stays off by default — keep it off unless you trust the agent. This is a security feature, not an inconvenience.

## What's New

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
