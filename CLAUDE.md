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
1. A `suspend fun rider_*` inside a `*Toolset : McpToolset` class
2. Annotated with `@McpTool` + `@McpDescription` (the cookbook recipe lives in the description)
3. Toolset registered in `src/main/resources/META-INF/plugin.xml` via `mcpServer.mcpToolset`
4. Returns a JSON string (built with `kotlinx.serialization.json`); errors via `mcpFail(...)`

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
├── ide/                     # P0+P4: IDE state, tool windows, terminals, TODO, endpoints, DB consoles
├── context/                 # P1: Programmer context (editors, cursor, selection)
├── testing/                 # P1: Test runner (run/wait/tree/rerun/filter)
├── debugger/                # P2: .NET debugger (breakpoints, evaluate, step)
├── runconfig/               # Run/Debug configuration CRUD
├── nuget/                   # P2: NuGet management
├── settings/                # P3: IDE inspections
├── profiling/               # dotTrace + dotMemory session control
└── admin/                   # Cache invalidation
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

See [AGENTS.md](AGENTS.md) — task→tool map, pagination, tool window map, polling pattern. Shared across all LLM agents. When adding a new tool or parameter, update AGENTS.md.

## Tool Design Rules

Recipes over tools (see CONTRACT.md): do not add a new `rider_*` tool when
a cookbook description on an existing one covers the scenario. A new tool
requires all three: (1) recipe cannot cover it, (2) reason recorded in the
commit message, (3) eval delta in `notes/evals` does not regress.

## Notes

`notes/` (git-ignored) holds internal working notes: analysis, plans, session logs.
When the developer asks to "create a note" (создать заметку), write it there.

## Feedback

`feedback/` (git-ignored, private) holds usage feedback and improvement proposals
for this plugin's tools. ANY agent using these tools must drop a note here after
a session with real friction — one file per session/issue, name `YYYY-MM-DD-short-topic.md`.
Template: context (1 line: what you tried) → what helped → what misrouted/missing
(tool names, error texts) → proposals by priority. Unprocessed files have no
`## Статус обработки` section; the maintainer adds it when triaging.
Feed confirmed items back into `notes/evals` scenarios and cookbook descriptions.

## Language

Code and comments — in English. Communication with the developer — in Russian.
