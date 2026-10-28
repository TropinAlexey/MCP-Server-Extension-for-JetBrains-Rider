# MCP Server Extension Plugin

IntelliJ/Rider plugin that extends JetBrains MCP Server with additional tools for full IDE control.

## Architecture

- **Type**: IntelliJ Platform Plugin (Kotlin)
- **Dependency**: `com.intellij.mcpServer` — base JetBrains MCP Server plugin
- **Extension point**: `com.intellij.mcpServer.mcpTool` — registers new MCP tools
- **Target IDEs**: JetBrains Rider 2026.2+ and RustRover 2026.2+ (2026.1 is served by the 1.2.x line on `release/1.2.x`)
- **JDK**: 21

## Contributor rules

Build commands, the new-tool pattern, agent- and OS-agnostic rules, tool hints, recipes over tools
and docs sync live in CONTRIBUTING.md (shared by every contributor and coding agent):

@CONTRIBUTING.md

## Structure

```
src/main/kotlin/com/github/tropin/ridermcp/
├── SessionManager.kt       # Shared polling session infrastructure
├── DotnetCli.kt            # `dotnet` command lines for the CLI configured in Rider
├── session/                 # rider_get_output: polling for test/NuGet/publish sessions
├── process/                 # P0: Process management
├── ide/                     # P0+P4: IDE state, tool windows, TODO, endpoints, DB consoles
├── context/                 # P1: Programmer context (editors, cursor, selection)
├── testing/                 # P1: Test runner (run/wait/tree/rerun/filter)
├── runconfig/               # Run/Debug configuration CRUD
├── nuget/                   # P2: NuGet management
├── settings/                # P3: IDE inspections
├── profiling/               # dotTrace + dotMemory session control
├── permissions/             # Tools → Configure Agent Permissions (client config edits)
└── admin/                   # Cache invalidation
```

## Agent Hints

See [AGENTS.md](AGENTS.md) — task→tool map, pagination, tool window map, polling pattern. Shared across all LLM agents. When adding a new tool or parameter, update AGENTS.md.

## Evals (maintainer)

A new tool also requires that the eval delta in `notes/evals` does not regress (CONTRACT.md, "Recipes over tools").

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
