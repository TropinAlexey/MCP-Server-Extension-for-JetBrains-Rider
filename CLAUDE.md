# MCP Server Extension Plugin

IntelliJ/Rider плагин, расширяющий JetBrains MCP Server дополнительными инструментами для полного управления IDE.

## Архитектура

- **Тип**: IntelliJ Platform Plugin (Kotlin)
- **Зависимость**: `com.intellij.mcpServer` — основной JetBrains MCP Server plugin
- **Extension point**: `com.intellij.mcpServer.mcpTool` — регистрация новых MCP tools
- **Target IDE**: JetBrains Rider 2026.1+
- **JDK**: 21

## Паттерн для новых tools

Каждый tool:
1. Наследует `AbstractMcpTool<Args>`
2. `Args` — `@Serializable` data class (или `NoArgs`)
3. Регистрируется в `src/main/resources/META-INF/plugin.xml`
4. Возвращает `Response(jsonString)` или `Response(error = "...")`

Для долгих операций (build, test run) — **polling pattern**:
- `start_*` → создаёт `OutputSession` через `SessionManager`, возвращает `sessionId`
- `get_*_output(sessionId)` → возвращает новые строки с момента последнего вызова
- Клиент поллит пока `status != "running"`

## Структура

```
src/main/kotlin/com/github/tropin/ridermcp/
├── SessionManager.kt       # Общая инфраструктура polling sessions
├── build/                   # P0: Build observability
├── process/                 # P0: Process management
├── ide/                     # P0: IDE state, tool windows, notifications
├── context/                 # P1: Programmer context (editors, cursor, selection)
├── testing/                 # P1: Test runner (TODO)
├── debugger/                # P2: .NET debugger (TODO)
├── nuget/                   # P2: NuGet management (TODO)
└── settings/                # P3: IDE settings management (TODO)
```

## Команды

```bash
./gradlew buildPlugin          # Собрать plugin zip
./gradlew runIde               # Запустить Rider с плагином для отладки
./gradlew verifyPlugin         # Проверить совместимость
```

## Naming convention

Все tool names начинаются с `rider_` чтобы не конфликтовать с основным MCP Server plugin.

## Подсказки для LLM-агентов

Карта: задача → tool:
- Курсор, выделение, открытые файлы, закладки → `rider_get_context`
- Недавно открытые файлы → `rider_get_recent_files`
- Готова ли IDE (индексация, занятость) → `rider_get_ide_state`
- Ошибки и предупреждения IDE → `rider_get_notifications`
- Содержимое любой панели (Build, Problems, etc.) → `rider_list_tool_windows` + `rider_get_tool_window_content`
- TODO/FIXME/HACK в коде → `rider_get_todos`
- API эндпоинты → `rider_get_endpoints`
- Команды в терминале IDE → `rider_list_terminals` + `rider_send_terminal_input`
- Сборка → `rider_start_build` + `rider_get_output` + `rider_cancel_build`
- Запуск тестов → `rider_run_tests` + `rider_get_output` + `rider_get_test_results` + `rider_rerun_failed_tests`
- NuGet пакеты → `rider_list_packages` / `rider_manage_package` / `rider_nuget_restore`
- Run/Debug конфигурации → `rider_create_run_config` / `rider_update_run_config` / `rider_delete_run_config`
- Отладка → `rider_set_breakpoint` / `rider_remove_breakpoint` / `rider_start_debug` / `rider_debug_state` / `rider_debug_evaluate` / `rider_debug_step`
- Процессы IDE → `rider_list_processes` / `rider_kill_process`
- Инспекции кода → `rider_list_inspections` / `rider_toggle_inspection`
- Кеши IDE → `rider_invalidate_caches`
- Профилирование (dotTrace) → `rider_profiling_state` / `rider_profiling_control`

Polling pattern: `rider_start_build`, `rider_run_tests`, `rider_nuget_restore`, `rider_start_debug`, `rider_rerun_failed_tests` возвращают `sessionId` — поллить через `rider_get_output` до `status != "running"`.

## Язык

Код и комментарии — на английском. Документация проекта (CLAUDE.md, TODO.md) — на русском.
Общение с разработчиком — на русском.
