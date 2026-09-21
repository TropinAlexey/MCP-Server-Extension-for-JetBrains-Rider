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
| Build solution | ✅ start + final status | ✅ streaming output, cancel, progress |
| See build errors | ✅ after build completes | ✅ real-time via Problems panel + deep text extraction |
| Process management | ❌ | ✅ list & kill IDE-managed processes |
| IDE state/progress | ❌ | ✅ indexing, building, publishing status |
| Tool windows | ❌ | ✅ read any tool window content |
| Notifications | ❌ | ✅ balloon messages, event log |
| Programmer context | Partial (open files, selection) | ✅ all-in-one: cursor, selection, surrounding code, open editors, bookmarks, unsaved files |
| Test runner | Partial (run via configs) | ✅ run, poll, results tree with stack traces, rerun failed |
| Run config CRUD | Partial (list & execute) | ✅ create, update, delete |
| .NET debugger | Partial (xdebug only) | ✅ breakpoints, evaluate, step, stack trace |
| NuGet management | ❌ | ✅ list, add, remove, restore |
| IDE settings | ❌ | ✅ inspections list & toggle |
| Terminal integration | Partial (execute commands) | ✅ list tabs, send input to specific terminals |
| TODO items | ❌ | ✅ project-wide TODO/FIXME/HACK |
| API endpoints | ❌ | ✅ HTTP routes from Endpoints panel |
| Cache invalidation | ❌ | ✅ invalidate caches & restart |
| dotTrace control | Partial (report analysis) | ✅ live session control: start/stop/detach profiling |

## How It Works

This is a companion to the built-in MCP server — not a replacement and not a fork. Install it, and your MCP client sees all of their tools as one unified set. Everything this plugin adds is prefixed with `rider_`, so there are no naming conflicts.

### Polling Pattern for Async Operations
MCP tools are synchronous (request → response). Long-running operations — builds, test runs, package restores, debug sessions — use a polling pattern instead of blocking:

1. `rider_start_build` / `rider_run_tests` / `rider_nuget_restore` / `rider_start_debug` → returns `{"sessionId": "build_1"}`
2. `rider_get_output("build_1")` → returns new lines since the last call, plus current status
3. Repeat until `status` is no longer `"running"`

## Available Tools (41)

### Build
| Tool | Description |
|---|---|
| `rider_start_build` | Start solution build, returns session ID for polling |
| `rider_cancel_build` | Cancel a running build |

### Test Runner
| Tool | Args | Description |
|---|---|---|
| `rider_run_tests` | `configName?`, `filter?`, `className?`, `methodName?` | Run tests. Filter by class/method or a raw `dotnet test --filter` expression. Omit everything to run all tests. If several test configurations exist, pass `configName` to pick one — but it runs the whole configuration (can be thousands of tests), so add a filter to scope it |
| `rider_run_tests_and_wait` | same filters + `timeoutMs?` | Run tests and wait in one call. Returns status, exit code, last lines, and a sessionId for `rider_get_test_results`. On timeout returns `running` — continue polling |
| `rider_get_test_results` | `sessionId` | Structured test results tree with statuses, durations, errors, stack traces |
| `rider_rerun_failed_tests` | — | Rerun previously failed tests |

### Shared Polling
| Tool | Args | Description |
|---|---|---|
| `rider_get_output` | `sessionId`, `maxLines?`, `fromEnd?`, `pattern?`, `offset?`, `allLines?` | Poll output of any async session (build, test, restore, debug). Returns new lines since the last call. `fromEnd` reads the tail, `pattern` searches with a regular expression, `offset` navigates page by page, `allLines` rereads the whole history |

### Process Management
| Tool | Description |
|---|---|
| `rider_list_processes` | List running processes with PID, command line, display name. Optional `type` filter (build/test/run) |
| `rider_kill_process` | Kill a process by display name |

### IDE State
| Tool | Args | Description |
|---|---|---|
| `rider_get_ide_state` | — | Progress indicators, active file, busy status |
| `rider_get_notifications` | `limit` (default 5) | Recent IDE notifications |
| `rider_list_tool_windows` | `all` (default false) | Tool windows (visible only by default) |
| `rider_list_tabs` | `windowId` | Tab names of a tool window + selected tab |
| `rider_get_tool_window_content` | `windowId`, `tab?`, `section?`, `maxLines?`, `offset?`, `fromEnd?`, `pattern?` | Text content of a tool window tab (editors, consoles, trees, lists). `section` reads one sub-tab only (e.g. `console` for Debug stdout). Defaults to selected tab, 200 lines |

### Run Configuration CRUD
| Tool | Args | Description |
|---|---|---|
| `rider_create_run_config` | `name`, `typeId`, `env?`, `programArgs?` | Create a run config. Use the built-in `get_run_configurations` for available types |
| `rider_update_run_config` | `name`, `env?`, `programArgs?`, `newName?` | Update env, args, or rename |
| `rider_delete_run_config` | `name` | Delete a run configuration |

### .NET Debugger
| Tool | Args | Description |
|---|---|---|
| `rider_set_breakpoint` | `file`, `line` | Set a line breakpoint (absolute or project-relative path) |
| `rider_remove_breakpoint` | `file`, `line` | Remove a line breakpoint |
| `rider_start_debug` | `configName?` | Start debug session, returns sessionId for polling |
| `rider_debug_state` | — | Session status, current position, stack trace with frame names |
| `rider_debug_evaluate` | `expression` | Evaluate expression in the current debug frame (debugger must be paused) |
| `rider_debug_step` | `action` | stepOver, stepInto, stepOut, resume, pause, stop |

### NuGet Management
| Tool | Args | Description |
|---|---|---|
| `rider_list_packages` | `project?`, `outdated?` | List installed NuGet packages, optionally show available updates |
| `rider_manage_package` | `action`, `name`, `project?`, `version?` | Add or remove a NuGet package |
| `rider_nuget_restore` | — | Run `dotnet restore`, returns sessionId for polling |

### Inspection Management
| Tool | Args | Description |
|---|---|---|
| `rider_list_inspections` | `keyword?`, `enabledOnly?` | Search inspections by keyword, filter by enabled state |
| `rider_toggle_inspection` | `shortName`, `enabled` | Enable or disable an inspection |

### Terminal
| Tool | Args | Description |
|---|---|---|
| `rider_list_terminals` | — | List open terminal tabs with names |
| `rider_send_terminal_input` | `text`, `tab?` | Send text input to a terminal tab (appends newline). Default tab 0 |

### Project Insights
| Tool | Args | Description |
|---|---|---|
| `rider_get_todos` | `limit?` | TODO/FIXME/HACK items from the TODO tool window (default limit 100) |
| `rider_get_endpoints` | — | API endpoints from the Endpoints tool window (HTTP method, URL, handler) |
| `rider_list_db_consoles` | — | Open database consoles with their data sources (name, id for the database tools, DBMS, URL). Ask this first when a DB console is open — no need to scan servers |
| `rider_execute_console` | `sql`, `console?`, `pageSize?`, `database?` | Execute SQL in an open console's data source, returns CSV. No IDE introspection required — use three-part names for other databases. Execution DB: console's current one by default, or pin it with `database` |

### dotTrace Profiling
| Tool | Args | Description |
|---|---|---|
| `rider_profiling_state` | — | dotTrace state: active session info (processes, snapshots, errors), opened snapshots, profiling availability |
| `rider_profiling_control` | `command`, `pid?` | Control active session. Commands: `start`, `stop` (save snapshot), `drop` (discard data), `detach`, `close`. Optional `pid` for multi-process |

### dotMemory Profiling
| Tool | Args | Description |
|---|---|---|
| `rider_memory_state` | — | dotMemory availability and active memory session status |
| `rider_memory_control` | `command`, `pid?`, `path?` | Control memory profiling. Commands: `snapshot` (collect, requires `pid`), `open` (open a `.dmw` workspace, requires `path`), `detach`, `kill` (kill the profiled process — destructive) |

### Admin
| Tool | Args | Description |
|---|---|---|
| `rider_invalidate_caches` | — | Invalidate IDE caches and restart. Use for stale highlighting, missing references, broken indexing |

### Programmer Context
| Tool | Description |
|---|---|
| `rider_get_context` | Active file + cursor + surrounding code + selection + open editors + bookmarks — all in one call |
| `rider_get_recent_files` | 20 most recently opened files |

### Tool Window Map

Every tool window follows the same model: **window → tabs → sections**.
Navigate it in three steps: `rider_list_tool_windows` → `rider_list_tabs` → `rider_get_tool_window_content`.

| Window | Tabs | Inside each tab |
|---|---|---|
| `Debug` | One tab per debug session (tab = session name, e.g. `LAPICore.Api`) | `Threads & Variables` tree, `Console` (debugged app stdout), `Debug Output` (debugger trace: `Loaded Assembly`, `Pdb file`, `Started/Exited Thread`) |
| `Run` | One tab per active execution (tab = descriptor name, e.g. `e2e tests`) | Process stdout/stderr console |
| `Build` | Build sessions | Compiler output; structured errors are also in `Problems` |
| `Problems` | Current file / project scope | Errors and warnings tree |
| `Terminal` | One tab per terminal | Shell console (read via content, write via `rider_send_terminal_input`) |
| `TODO` | Scope filter | TODO/FIXME/HACK tree (also via `rider_get_todos`) |
| `Endpoints` | — | HTTP routes tree/list (also via `rider_get_endpoints`) |
| `Services` | Run dashboard entries | Service/run consoles. May report `has no content` until opened once in Rider (`View → Tool Windows → Services`) |
| `NuGet`, `Database`, others | Varies | Generic text/tree extraction (see rules below) |

How content is returned (`rider_get_tool_window_content`):
- Every response lists `availableSections` — the sub-tabs found inside — so there is no need to guess their names.
- `section` reads a single sub-tab by a name fragment (case-insensitive), e.g. `section=console`.
- The Debug window exposes two dedicated sections: `Console` (your application's own output) and `Debug Output` (the debugger trace — loaded assemblies, thread events).
- Trees and lists are flattened into indented plain text; long outputs can be paged with `maxLines`, `fromEnd`, `pattern`, and `offset`.
- If a section name is not found, the error lists the sections that actually exist.
- A window that has never been opened in Rider (e.g. Services) reports that, with a hint to open it first via View → Tool Windows.

Recipes:
```jsonc
// Application output of the active debug session, last 50 lines (not the debugger trace)
{ "windowId": "Debug", "section": "console", "maxLines": 50, "fromEnd": true }

// Debugger trace tail (assemblies, threads)
{ "windowId": "Debug", "section": "output", "maxLines": 30, "fromEnd": true }

// Process output of a finished run (tab name from rider_list_tabs("Run"))
{ "windowId": "Run", "tab": "e2e tests", "maxLines": 30, "fromEnd": true }
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
| "Configuration '…' not found" | Names must match exactly — list them with the built-in `get_run_configurations` |
| `rider_list_db_consoles` returns `[]` | Open the console in an editor tab first (double-click it in the Database tool window) |
| A tool returns a file path you cannot read | Read only paths from `rider_get_context` (project-relative, always readable); absolute local path as fallback. Never feed one tool's path into another tool blindly |
| Target database missing from the schema list | The list is the introspected subset, not the truth. Run the query through any introspected database with three-part names (`db.schema.table`) and confirm via `SELECT name FROM sys.databases` |
| Heavy SQL (`COUNT LIKE` over `CAST`, full-table scans) | Confirm the database context first, then slice: `TOP`, date/id ranges, `EXISTS` instead of `COUNT LIKE`, `pageSize` for paging. Deliberate cancel is normal practice, not a failure |
| Tempted by generic `execute_tool` | Don't — it has no action listing and can't reach DB consoles or run profiles. Use the `rider_*` tool for the job |

## Limitations & Privacy

- Rider must be running with a project open — the tools observe and control the current project.
- Enabling the MCP server grants external applications access to your open projects (you confirm this in the dialog at enable time). Review which tools are exposed under **Settings → Tools → MCP Server → Exposed Tools**.
- What the tools can see: file contents, cursor and selection, open editors, tool window text (including consoles and outputs), notifications, database console bindings and query results. What they never do: nothing leaves your machine through this plugin — it only serves the local MCP server; network calls happen only where you ask (NuGet restore, package checks).
- What the tools can change: sending terminal input, killing processes, editing run configs, toggling inspections, executing SQL, profiling sessions, restarting the IDE after cache invalidation. Read-only tools (state, content, lists, context) never change anything.
- Running commands without confirmation is opt-in ("brave mode" in the MCP server settings) and stays off by default — keep it off unless you trust the agent. This is a security feature, not an inconvenience.

## What's New

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
