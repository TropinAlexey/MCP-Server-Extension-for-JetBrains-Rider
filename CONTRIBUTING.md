# Contributing

Thanks for helping. This file is for people and coding agents working on the plugin's code;
[AGENTS.md](AGENTS.md) is the guide for agents *using* the tools.

## Build and test (Windows, macOS, Linux)

Requirements: a JDK 17+ to start Gradle (JDK 21 for the build is downloaded automatically) and,
for `runIde`, nothing else — the Rider SDK comes from Maven.

| Task | Linux / macOS | Windows |
|---|---|---|
| Unit tests + tool-doc checks | `./gradlew check` | `gradlew.bat check` |
| Plugin zip (`build/distributions/`) | `./gradlew buildPlugin` | `gradlew.bat buildPlugin` |
| Rider with the plugin loaded | `./gradlew runIde` | `gradlew.bat runIde` |
| Plugin Verifier | `./gradlew verifyPlugin` | `gradlew.bat verifyPlugin` |

- `verifyPlugin` uses an installed Rider when `RIDER_PATH` points at it (default: `/Applications/Rider.app/Contents`),
  otherwise the Rider version the build compiles against. `-PverifyAllRiders` checks every Rider line the plugin claims.
- CI runs `check buildPlugin` on Ubuntu, Windows and macOS, and the Plugin Verifier on Ubuntu. Keep all three green.

## Rules for code

### Agent-agnostic
The plugin serves any MCP client (Claude Code, Codex, Gemini CLI, Copilot, Cursor, Junie, …).
Tool descriptions, AGENTS.md, README and IDE UI use neutral wording ("agent", "MCP client");
client-specific details appear only as a list covering several clients (e.g. permission setup).
No feature may require one particular client.

### OS-agnostic
- Paths via `java.nio.file.Path`; never hard-code `/`, `\`, `/tmp`, `C:\` or a user's home layout.
  List separators: `File.pathSeparatorChar`.
- External processes via `GeneralCommandLine` (it loads the user's shell environment, unlike `ProcessBuilder`
  in an IDE started from the macOS Dock). Run `dotnet` through `DotnetCli` — it uses the CLI configured in Rider.
- Client config locations honor the clients' own overrides (`CLAUDE_CONFIG_DIR`, `CODEX_HOME`); read them
  through an injectable `env` so tests stay hermetic.
- SQL hints and errors name the DBMS when a construct is dialect-specific (`TOP` vs `LIMIT`).

### New tool pattern
1. A `suspend fun rider_*` inside a `*Toolset : McpToolset` class (the `rider_` prefix avoids clashes with the built-in server).
2. Annotated with `@McpTool` + `@McpDescription` — the cookbook recipe lives in the description (≤ 1100 chars, enforced by `checkToolDocs`).
3. Toolset registered in `src/main/resources/META-INF/plugin.xml` via `mcpServer.mcpToolset`.
4. Returns a JSON string (`kotlinx.serialization.json`); errors via `mcpFail(...)`.
5. `@McpToolHints` before `@McpTool`: `readOnlyHint = TRUE` only for tools that never change anything — no
   file-writing parameter either (`outputFile` disqualifies); `destructiveHint = TRUE` for kill/delete/arbitrary SQL.
   Clients auto-approve on them.
6. Long-running work uses the polling pattern: `start_*` creates an `OutputSession` via `SessionManager` and returns
   `sessionId`; the client polls `rider_get_output(sessionId)` until `status != "running"`.

### Recipes over tools
Do not add a `rider_*` tool when a cookbook description on an existing one covers the scenario. A new tool needs
all of: the recipe cannot cover it, the reason is in the commit message, and the eval check below does not regress.
Response shapes, tool names and hints are a public contract — see [CONTRACT.md](CONTRACT.md).

### Docs
When adding a tool or parameter, update README (tool table — `checkToolDocs` fails on a missing tool) and AGENTS.md
(task → tool routing). User-visible changes go into README "What's New" and `CHANGELOG.html` (Marketplace change notes).

## Evals

Tool-routing evals run against a private fixture and are executed by the maintainer before a release. In a pull
request, describe how you checked the change by hand (the prompt you gave an agent, the tool calls it made, the result).

## Feedback and issues

Bugs, misrouted tools and confusing descriptions: [GitHub Issues](https://github.com/TropinAlexey/MCP-Server-Extension-for-JetBrains-Rider/issues).
Code and comments are in English.
