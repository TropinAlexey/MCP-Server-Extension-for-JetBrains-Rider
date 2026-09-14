<p align="center">
  <img src="icon.png" alt="MCP Server Extension" width="80">
</p>

<h1 align="center">MCP Server Extension</h1>

<p align="center">
  <a href="https://plugins.jetbrains.com/plugin/34264-mcp-server-extension"><img src="https://img.shields.io/jetbrains/plugin/v/34264?style=flat-square&label=marketplace" alt="JetBrains Marketplace"></a>
  <a href="https://github.com/TropinAlexey/rider-mcp/releases/latest"><img src="https://img.shields.io/github/v/release/TropinAlexey/rider-mcp?style=flat-square&label=version" alt="Version"></a>
  <a href="https://github.com/TropinAlexey/rider-mcp/actions/workflows/build.yml"><img src="https://img.shields.io/github/actions/workflow/status/TropinAlexey/rider-mcp/build.yml?style=flat-square" alt="Build"></a>
  <img src="https://img.shields.io/badge/Rider-2024.3%2B-blue?style=flat-square&logo=jetbrains" alt="Rider 2024.3+">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-yellow?style=flat-square" alt="MIT License"></a>
</p>

<p align="center">
  A JetBrains Rider plugin that extends the stock <a href="https://github.com/JetBrains/mcp-server-plugin">MCP Server Plugin</a> with full IDE observability and control tools.
</p>

**Current version: 0.9.0** — API compatibility fixes, CI & Marketplace. [What's New →](#whats-new)

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
| Programmer context | ❌ | ✅ open editors, cursor, selection |
| Test runner | ❌ | ✅ run, poll, results tree with stack traces, rerun failed |
| Run config CRUD | ❌ | ✅ create, update, delete |
| .NET debugger | Partial (xdebug only) | ✅ breakpoints, evaluate, step, stack trace |
| NuGet management | ❌ | ✅ list, add, remove, restore |
| IDE settings | ❌ | ✅ inspections list & toggle |

## Architecture

This is **not a fork** of the JetBrains MCP Server plugin. It's a separate plugin that extends it through the official `mcpTool` extension point:

```
MCP Client ←MCP→ JS proxy (mcp-jetbrains) ←HTTP→ Rider JVM
                                                    ├── MCP Server Plugin (JetBrains)
                                                    │   └── stock tools (~30)
                                                    └── MCP Server Extension (this plugin)
                                                        └── additional tools (28)
```

All tools from both plugins appear as a unified set in any MCP client. Our tools are prefixed with `rider_` to avoid naming conflicts.

### Polling Pattern for Async Operations

MCP tools are synchronous (request → response). For long-running operations like builds and test runs, we use a polling pattern:

1. `rider_start_build` / `rider_run_tests` → returns `{"sessionId": "build_1"}`
2. `rider_get_output("build_1")` → returns new lines since last call
3. Repeat until `status` is no longer `"running"`

## Available Tools (28)

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

### IDE State (4 tools)
| Tool | Args | Description |
|---|---|---|
| `rider_get_ide_state` | — | Progress indicators, active file, busy status |
| `rider_get_notifications` | `limit` (default 5) | Recent IDE notifications |
| `rider_list_tool_windows` | `all` (default false) | Tool windows (visible only by default) |
| `rider_get_tool_window_content` | `windowId`, `tab?`, `maxLines?` | Text content of a tool window (editors, consoles, trees, lists). Defaults to selected tab, 200 lines |

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

### Programmer Context (2 tools)
| Tool | Description |
|---|---|
| `rider_get_context` | Active file + cursor + surrounding code + selection + open editors + bookmarks — all in one call |
| `rider_get_recent_files` | 20 most recently opened files |

### Token Efficiency

Responses are optimized to minimize token consumption by the MCP client:
- **Compact JSON** — false/empty fields omitted, only non-default values included
- **Filtered defaults** — `rider_list_tool_windows` returns only visible windows, `rider_list_processes` only running ones
- **Combined context** — `rider_get_context` replaces 5 separate tools (editors + cursor + selection + bookmarks) in a single round-trip
- **Unified polling** — `rider_get_output` works for any async session (build, test), no duplicate poll tools
- **Capped payloads** — notifications default to 5, tool windows to visible-only

## Installation

### Prerequisites
- JetBrains Rider 2024.3+
- [MCP Server Plugin](https://plugins.jetbrains.com/plugin/26071-mcp-server) installed and enabled

### From Source

```bash
git clone https://github.com/TropinAlexey/rider-mcp.git
cd rider-mcp

# Linux / macOS
./gradlew buildPlugin

# Windows
gradlew.bat buildPlugin
```

Then install: **Rider → Settings → Plugins → ⚙️ → Install Plugin from Disk → select `build/distributions/rider-mcp-*.zip`**

### Development

```bash
# Run Rider with the plugin loaded (Linux/macOS)
./gradlew runIde

# Windows
gradlew.bat runIde
```

## Roadmap

See [TODO.md](TODO.md) for the full prioritized roadmap.

**Next up:**
- P3: Terminal streaming, Tool Windows deep integration
- ~~CI (GitHub Actions), JetBrains Marketplace publish~~ ✅

## What's New

### v0.8.0

**NuGet Management**
- `rider_list_packages` — list installed packages with versions, `outdated` flag for available updates
- `rider_manage_package` — add or remove NuGet packages via dotnet CLI
- `rider_nuget_restore` — async restore with polling via `rider_get_output`

**Inspection Management**
- `rider_list_inspections` — search inspections by keyword, filter by enabled state
- `rider_toggle_inspection` — enable/disable inspections by shortName

### v0.7.0

**.NET Debugger**
- `rider_set_breakpoint` / `rider_remove_breakpoint` — line breakpoints by file:line
- `rider_start_debug` — launch debug session with polling via `rider_get_output`
- `rider_debug_state` — session status (running/paused/stopped), current position, stack trace with frame names
- `rider_debug_evaluate` — evaluate expressions in the current debug frame
- `rider_debug_step` — stepOver, stepInto, stepOut, resume, pause, stop

### v0.6.0

**Test Filtering**
- `rider_run_tests` now accepts `className`, `methodName`, or raw `filter` expression
- Filtered runs use `dotnet test --filter` directly for reliable .NET test filtering
- Without filter — still runs via Rider UI configuration as before

**Process Type Filtering**
- `rider_list_processes` now accepts optional `type` (build/test/run) and infers type from display name and command line

**Bug Fixes**
- Rename uniqueness check in `rider_update_run_config` — prevents renaming to an existing config name

### v0.5.0

**Test Results Tree**
- `rider_get_test_results` — structured test tree with statuses (passed/failed/ignored), durations, error messages, and full stack traces
- Extracted from IntelliJ's `SMTestProxy` tree after test execution completes

**Process Observability**
- `rider_list_processes` now includes PID and command line for each process

### v0.4.0

**Run/Debug Configuration CRUD**
- `rider_create_run_config` — create with typeId, env vars, program args
- `rider_update_run_config` — modify env, args, or rename
- `rider_delete_run_config` — delete by name
- Uses `CommonProgramRunConfigurationParameters` for type-safe parameter access

### v0.3.0

**Deep Tool Window Content Extraction**
- `rider_get_tool_window_content` now extracts real text from any tool window — editors, consoles, trees, lists
- New params: `tab` (specific tab name), `maxLines` (default 200)
- Read Build Output, Problems, Event Log, Run/Debug output directly without polling

### v0.2.0

**Test Runner**
- `rider_run_tests` — auto-detects test config or accepts `configName`; streams output via polling
- `rider_rerun_failed_tests` — reruns previously failed tests via IDE action

**Token Optimizations**
- Merged 5 context tools → `rider_get_context` (single round-trip for file + cursor + selection + editors + bookmarks)
- Unified polling: `rider_get_build_output` → `rider_get_output` (works for build, test, any future session)
- Compact JSON responses — omit false/empty/null fields
- `rider_get_notifications`: default limit 5, configurable via `limit` param
- `rider_list_tool_windows`: visible-only by default, `all` param for full list
- `rider_list_processes`: only running processes, response is array of names
- Removed tool windows from `rider_get_ide_state` (use `rider_list_tool_windows`)

**Bug Fixes**
- Build polling now works: `ProjectTaskManager` with `onSuccess`/`onError` callbacks instead of fire-and-forget action
- Thread safety: `@Volatile` on `OutputSession.status`/`exitCode`/`progress`
- `CancelBuildTool`: proper `SimpleDataContext` with project binding
- Session cleanup: timestamp-based TTL (10min) instead of remove-all
- `canKill` field is now boolean, not string

### v0.1.0

Initial release — 15 tools: build observability, process management, IDE state, programmer context.

## License

MIT
