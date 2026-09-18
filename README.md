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
  A JetBrains Rider plugin that extends the stock <a href="https://github.com/JetBrains/mcp-server-plugin">MCP Server Plugin</a> with full IDE observability and control tools by AI agents.
</p>

[What's New →](#whats-new)

## Why This Exists

The stock JetBrains MCP Server plugin provides ~30 tools, but most of them either duplicate what MCP clients already do better natively (file reading/editing, git operations) or target specific ecosystems (Unreal Engine, Godot, xdebug/PHP).

**The core problem: your AI assistant is blind to what happens inside the IDE.**

A programmer staring at Rider sees dozens of information surfaces simultaneously — Build Output streaming in real time, Problems panel lighting up with errors, test runner trees expanding, progress bars showing indexing status, notifications popping up about package restores. An MCP client sees none of this.

When a build hangs, you see the output frozen at "Building Project7..." — your assistant sees nothing. When publish fails silently, you see a red notification balloon — your assistant sees nothing. When zombie MSBuild nodes eat CPU, you see Activity Monitor — your assistant sees nothing.

This plugin bridges that gap. It gives any MCP-compatible client (Claude Code, Cursor, Windsurf, Continue, custom agents) the same situational awareness a programmer has — the ability to see what the IDE is doing, what it's showing, and what's happening in the background.

### What the stock MCP plugin provides vs. what this adds

| Capability | Stock MCP Plugin | This Extension |
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

## Architecture

This is **not a fork** of the JetBrains MCP Server plugin. It's a separate plugin that extends it through the official `mcpTool` extension point:

```
MCP Client ←MCP→ JS proxy (mcp-jetbrains) ←HTTP→ Rider JVM
                                                    ├── MCP Server Plugin (JetBrains)
                                                    │   └── stock tools (~30)
                                                    └── MCP Server Extension (this plugin)
                                                         └── additional tools (36)
```

All tools from both plugins appear as a unified set in any MCP client. Our tools are prefixed with `rider_` to avoid naming conflicts.

### Polling Pattern for Async Operations

MCP tools are synchronous (request → response). For long-running operations like builds and test runs, we use a polling pattern:

1. `rider_start_build` / `rider_run_tests` → returns `{"sessionId": "build_1"}`
2. `rider_get_output("build_1")` → returns new lines since last call
3. Repeat until `status` is no longer `"running"`

## Available Tools (36)

### Build (3 tools)
| Tool | Description |
|---|---|
| `rider_start_build` | Start solution build, returns session ID |
| `rider_cancel_build` | Cancel running build |

### Test Runner (3 tools)
| Tool | Args | Description |
|---|---|---|
| `rider_run_tests` | `configName?`, `className?`, `methodName?`, `filter?` | Run tests. Supports filtering by class/method or raw `dotnet test --filter` expression |
| `rider_get_test_results` | `sessionId` | Structured test results tree with statuses, durations, errors, stack traces |
| `rider_rerun_failed_tests` | — | Rerun previously failed tests |

### Shared Polling
| Tool | Args | Description |
|---|---|---|
| `rider_get_output` | `sessionId` | Poll output for any async session (build, test). Returns new lines since last call |

### Process Management (2 tools)
| Tool | Description |
|---|---|
| `rider_list_processes` | List running processes with PID, command line, display name. Optional `type` filter (build/test/run) |
| `rider_kill_process` | Kill a process by display name |

### IDE State (5 tools)
| Tool | Args | Description |
|---|---|---|
| `rider_get_ide_state` | — | Progress indicators, active file, busy status |
| `rider_get_notifications` | `limit` (default 5) | Recent IDE notifications |
| `rider_list_tool_windows` | `all` (default false) | Tool windows (visible only by default) |
| `rider_list_tabs` | `windowId` | Tab names of a tool window + selected tab |
| `rider_get_tool_window_content` | `windowId`, `tab?`, `section?`, `maxLines?`, `offset?`, `fromEnd?`, `pattern?` | Text content of a tool window tab (editors, consoles, trees, lists). `section` reads one sub-tab only (e.g. `console` for Debug stdout). Defaults to selected tab, 200 lines |

### Run Configuration CRUD (3 tools)
| Tool | Args | Description |
|---|---|---|
| `rider_create_run_config` | `name`, `typeId`, `env?`, `programArgs?` | Create a run config. Use stock `get_run_configurations` for available types |
| `rider_update_run_config` | `name`, `env?`, `programArgs?`, `newName?` | Update env, args, or rename |
| `rider_delete_run_config` | `name` | Delete a run configuration |

### .NET Debugger (6 tools)
| Tool | Args | Description |
|---|---|---|
| `rider_set_breakpoint` | `file`, `line` | Set a line breakpoint (absolute or project-relative path) |
| `rider_remove_breakpoint` | `file`, `line` | Remove a line breakpoint |
| `rider_start_debug` | `configName?` | Start debug session, returns sessionId for polling |
| `rider_debug_state` | — | Session status, current position, stack trace with frame names |
| `rider_debug_evaluate` | `expression` | Evaluate expression in current debug frame |
| `rider_debug_step` | `action` | stepOver, stepInto, stepOut, resume, pause, stop |

### NuGet Management (3 tools)
| Tool | Args | Description |
|---|---|---|
| `rider_list_packages` | `project?`, `outdated?` | List installed NuGet packages, optionally show available updates |
| `rider_manage_package` | `action`, `name`, `project?`, `version?` | Add or remove a NuGet package |
| `rider_nuget_restore` | — | Run `dotnet restore`, returns sessionId for polling |

### Inspection Management (2 tools)
| Tool | Args | Description |
|---|---|---|
| `rider_list_inspections` | `keyword?`, `enabledOnly?` | Search inspections by keyword, filter by enabled state |
| `rider_toggle_inspection` | `shortName`, `enabled` | Enable or disable an inspection |

### Terminal (2 tools)
| Tool | Args | Description |
|---|---|---|
| `rider_list_terminals` | — | List open terminal tabs with names |
| `rider_send_terminal_input` | `text`, `tab?` | Send text input to a terminal tab (appends newline). Default tab 0 |

### Project Insights (2 tools)
| Tool | Args | Description |
|---|---|---|
| `rider_get_todos` | `limit?` | TODO/FIXME/HACK items from the TODO tool window (default limit 100) |
| `rider_get_endpoints` | — | API endpoints from the Endpoints tool window (HTTP method, URL, handler) |

### dotTrace Profiling (2 tools)
| Tool | Args | Description |
|---|---|---|
| `rider_profiling_state` | — | dotTrace state: active session info (processes, snapshots, errors), opened snapshots, profiling availability |
| `rider_profiling_control` | `command`, `pid?` | Control active session. Commands: `start`, `stop` (save snapshot), `drop` (discard data), `detach`, `close`. Optional `pid` for multi-process |

### Admin (1 tool)
| Tool | Args | Description |
|---|---|---|
| `rider_invalidate_caches` | — | Invalidate IDE caches and restart. Use for stale highlighting, missing references, broken indexing |

### Programmer Context (2 tools)
| Tool | Description |
|---|---|
| `rider_get_context` | Active file + cursor + surrounding code + selection + open editors + bookmarks — all in one call |
| `rider_get_recent_files` | 20 most recently opened files |

### Tool Window Map

Every tool window follows the same model: **window → tabs → Swing component tree**.
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

Text extraction rules (`rider_get_tool_window_content`):
- Sub-tabs (`JBTabs` and `JTabbedPane`) are all visited and marked with `--- <title> ---` headers — read the headers to see which sections exist.
- Every response includes `availableSections` so you don't have to guess section names.
- Editor-based consoles (run/debug output) are read as plain text.
- Trees (`JTree`) are flattened with indent; lists (`JList`) item by item.
- `section` reads a single sub-tab by title substring (case-insensitive), e.g. `section=console`.
  Exception: the Debug `Console` is not reliably a Swing sub-tab, so for `windowId=Debug` + `section=console`
  the text is fetched via the debugger API (source of truth is the session `consoleView` document) instead of the component tree.
- If `section` is not found, the error lists the actually available sub-tab titles (same as `availableSections`).

Recipes:
```jsonc
// App stdout of the active debug session, last 50 lines (NOT the debugger trace)
{ "windowId": "Debug", "section": "console", "maxLines": 50, "fromEnd": true }

// Debugger trace tail (assemblies, threads)
{ "windowId": "Debug", "section": "output", "maxLines": 30, "fromEnd": true }

// Process output of a finished run (tab name from rider_list_tabs("Run"))
{ "windowId": "Run", "tab": "e2e tests", "maxLines": 30, "fromEnd": true }
```

### Token Efficiency

Responses are optimized to minimize token consumption by the MCP client:
- **Compact JSON** — false/empty fields omitted, only non-default values included
- **Filtered defaults** — `rider_list_tool_windows` returns only visible windows, `rider_list_processes` only running ones
- **Combined context** — `rider_get_context` replaces 5 separate tools (editors + cursor + selection + bookmarks) in a single round-trip
- **Unified polling** — `rider_get_output` works for any async session (build, test), no duplicate poll tools
- **Capped payloads** — notifications default to 5, tool windows to visible-only

## Installation

### Prerequisites
- JetBrains Rider 2026.1+
- [MCP Server Plugin](https://plugins.jetbrains.com/plugin/26071-mcp-server) installed and enabled

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

## What's New

### v1.0.5

- Feature: `rider_get_tool_window_content` — `section` parameter reads one sub-tab only. `section=console` on the Debug window returns the debugged app's stdout via the debugger API (source of truth is the session `consoleView` document, previously only the `Debug Output` trace was reachable). Every response includes `availableSections`; sub-tab search covers both `JBTabs` and `JTabbedPane`
- Feature: new `rider_list_tabs` tool — list tab names + selected tab of any tool window (36 tools now)
- Fix: EDT violations in Terminal/TODO/Endpoints/IDE-state reads (`invokeAndWait`); `Services` no longer crashes, reports `has no content` with a hint until opened in UI
- Fix: `rider_get_output` reports `totalLines`/`returnedRange` in session-wide coordinates
- Docs: full tool window map (window → tabs → sections) with Debug/Run recipes

### v1.0.4

- Feature: `rider_get_tool_window_content` — pagination, tail reading (`fromEnd`), regex filtering (`pattern`), and offset-based navigation (`offset`)
- Feature: `rider_get_output` — same pagination params plus `allLines` to read entire session history
- Response always includes `totalLines` and `returnedRange` metadata
- Replaced `truncateMode` with more flexible `fromEnd`/`offset`/`pattern` params

### v1.0.3

- Feature: `rider_get_tool_window_content` now supports `truncateMode` parameter (START/END/MIDDLE/NONE) — use START to get the last N lines (best for Debug/Build logs where errors are at the end)
- Feature: `rider_get_output` now supports `maxLines` and `truncateMode` parameters for consistent truncation across all output tools
- Response now includes `totalLines` count when output is truncated

### v1.0.2

- Fix: marketplace "What's New" now reads from `CHANGELOG.html` instead of hardcoded string
- CI: upgrade `setup-java` v4 → v5 (Node.js 20 deprecation)
- Docs: correct stock MCP plugin capabilities in comparison table

### v1.0.1

- Fix: `rider_get_tool_window_content` now reads all internal sub-tabs (JBTabs) — Debug Console, Run output and other sub-panels are no longer invisible
- Fix: Editor-based console text extraction — previous `is Editor` check was dead code (`Editor` is not a Swing `Component`); replaced with `EditorComponentImpl`

### v1.0.0

- New logo
- 35 tools, Rider 2026.1+

<details>
<summary>Older releases</summary>

### v0.2.2

- Minimum version raised to Rider 2026.1+ — `intellij.testRunner.plugin` requires 2026+ (not available in 2025.3)

### v0.2.1

- Removed `rider_manage_plugin` — all plugin management APIs are `@Internal`, no public alternative exists (35 tools now)
- Plugin Verifier: **Compatible** on Rider 2026.2, zero internal/experimental API usages

### v0.2.0

**Canonical build setup & Rider 2026 compatibility**
- Gradle 8.13 → 9.5.0, IntelliJ Platform Gradle Plugin 2.5.0 → 2.16.0
- Canonical project structure per JetBrains template: `settings.gradle.kts` with `pluginManagement`/`dependencyResolutionManagement`, JDK toolchain, Gradle configuration & build cache
- Added V2 `<dependencies>` block for `intellij.testRunner.plugin` — resolves smRunner class verification on 2026.x
- Plugin Verifier: **Compatible** on Rider 2026.2, zero compatibility problems, zero deprecated API usages

### v0.11.0 — dotTrace Profiling
- `rider_profiling_state` / `rider_profiling_control` — dotTrace session management

### v0.10.0 — Terminal & Insights
- `rider_list_terminals` / `rider_send_terminal_input`, `rider_get_todos`, `rider_get_endpoints`

### v0.9.0 — API Compatibility
- Deprecated API cleanup, renamed to "MCP Server Extension", CI + Marketplace publishing

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
- `rider_run_tests` / `rider_rerun_failed_tests`, merged 5 context tools → 1, compact JSON

### v0.1.0
- Initial release — 15 tools

</details>

## License

MIT
