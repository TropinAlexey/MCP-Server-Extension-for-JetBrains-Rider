# MCP Server Extension Plugin

IntelliJ/Rider plugin that extends JetBrains MCP Server with additional tools for full IDE control.

## Architecture

- **Type**: IntelliJ Platform Plugin (Kotlin)
- **Dependency**: `com.intellij.mcpServer` — base JetBrains MCP Server plugin
- **Extension point**: `com.intellij.mcpServer.mcpTool` — registers new MCP tools
- **Target IDE**: JetBrains Rider 2026.1+
- **JDK**: 21

## New Tool Pattern

Each tool:
1. Extends `AbstractMcpTool<Args>`
2. `Args` — `@Serializable` data class (or `NoArgs`)
3. Registered in `src/main/resources/META-INF/plugin.xml`
4. Returns `Response(jsonString)` or `Response(error = "...")`

For long-running operations (build, test run) — **polling pattern**:
- `start_*` → creates `OutputSession` via `SessionManager`, returns `sessionId`
- `get_*_output(sessionId)` → returns new lines since last call
- Client polls until `status != "running"`

## Structure

```
src/main/kotlin/com/github/tropin/ridermcp/
├── SessionManager.kt       # Shared polling session infrastructure
├── build/                   # P0: Build observability
├── process/                 # P0: Process management
├── ide/                     # P0: IDE state, tool windows, notifications
├── context/                 # P1: Programmer context (editors, cursor, selection)
├── testing/                 # P1: Test runner (TODO)
├── debugger/                # P2: .NET debugger (TODO)
├── nuget/                   # P2: NuGet management (TODO)
└── settings/                # P3: IDE settings management (TODO)
```

## Commands

```bash
./gradlew buildPlugin          # Build plugin zip
./gradlew runIde               # Run Rider with plugin for debugging
./gradlew verifyPlugin         # Check compatibility
```

## Naming Convention

All tool names start with `rider_` to avoid conflicts with the base MCP Server plugin.

## Agent Hints

Task → tool map:
- Cursor, selection, open files, bookmarks → `rider_get_context`
- Recently opened files → `rider_get_recent_files`
- IDE readiness (indexing, busy) → `rider_get_ide_state`
- IDE errors and warnings → `rider_get_notifications`
- Any panel content (Build, Problems, etc.) → `rider_list_tool_windows` + `rider_get_tool_window_content` (see **Pagination** below)
- TODO/FIXME/HACK in code → `rider_get_todos`
- API endpoints → `rider_get_endpoints`
- IDE terminal commands → `rider_list_terminals` + `rider_send_terminal_input`
- Build → `rider_start_build` + `rider_get_output` + `rider_cancel_build`
- Run tests → `rider_run_tests` + `rider_get_output` + `rider_get_test_results` + `rider_rerun_failed_tests`
- NuGet packages → `rider_list_packages` / `rider_manage_package` / `rider_nuget_restore`
- Run/Debug configurations → `rider_create_run_config` / `rider_update_run_config` / `rider_delete_run_config`
- Debugging → `rider_set_breakpoint` / `rider_remove_breakpoint` / `rider_start_debug` / `rider_debug_state` / `rider_debug_evaluate` / `rider_debug_step`
- IDE processes → `rider_list_processes` / `rider_kill_process`
- Code inspections → `rider_list_inspections` / `rider_toggle_inspection`
- IDE caches → `rider_invalidate_caches`
- Profiling (dotTrace) → `rider_profiling_state` / `rider_profiling_control`

Polling pattern: `rider_start_build`, `rider_run_tests`, `rider_nuget_restore`, `rider_start_debug`, `rider_rerun_failed_tests` return `sessionId` — poll via `rider_get_output` until `status != "running"`.

### Pagination & filtering (`rider_get_tool_window_content` and `rider_get_output`)

Both tools share the same pagination parameters. Response always includes `totalLines` and `returnedRange` so you know exactly what you got and how much more is available.

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

## Language

Code and comments — in English. Communication with the developer — in Russian.
