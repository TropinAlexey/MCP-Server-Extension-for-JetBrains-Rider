# MCP Server Extension — Roadmap

## P0 — Критически нужно

### Build & Publish наблюдаемость
- [x] `rider_start_build` — запуск сборки с session ID
- [x] `rider_get_build_output` — polling вывода сборки
- [x] `rider_cancel_build` — отмена текущей сборки
- [~] ~~CompilerMessageHandler~~ — покрывается `rider_get_tool_window_content("Build")`
- [~] ~~Publish / MSBuild verbosity~~ — Rider не имеет VS-style publish; `dotnet publish` через терминал

### Process management
- [x] `rider_list_processes` — список процессов Rider
- [x] `rider_kill_process` — убить процесс по имени
- [x] Показывать PID и command line процессов
- [x] Фильтрация по типу (build, test, run) через параметр `type`

### IDE State
- [x] `rider_get_ide_state` — прогресс-индикаторы, активный файл, tool windows
- [x] `rider_get_notifications` — balloon уведомления и event log
- [x] `rider_list_tool_windows` — список tool windows с состоянием
- [x] `rider_get_tool_window_content` — содержимое tool window (базовое)
- [x] Глубокая экстракция контента для Build Output, Problems, Event Log
- [~] ~~Подписка на уведомления~~ — `rider_get_notifications` покрывает через периодический вызов

## P1 — Основной рабочий flow

### Programmer Context
- [x] `rider_get_context` — объединённый: файл + курсор + код + выделение + вкладки + закладки
- [x] `rider_get_recent_files` — последние открытые файлы

### Test Runner
- [x] `rider_run_tests` — запуск тестов (auto-detect config или по имени)
- [x] `rider_get_output` — единый polling tool для build/test/любых сессий
- [x] `rider_rerun_failed_tests` — перезапуск упавших через IDE action
- [x] Дерево результатов с stack traces (извлечение из SMTestProxy)
- [x] Фильтрация: запуск тестов по className/methodName/filter (через `dotnet test --filter`)

### Run/Debug Configuration Management
- [x] `rider_create_run_config` — создание новой конфигурации
- [x] `rider_update_run_config` — изменение параметров (env, args, rename)
- [x] `rider_delete_run_config` — удаление

## P2 — Расширенные возможности

### .NET Debugger
- [x] `rider_set_breakpoint` / `rider_remove_breakpoint` — line breakpoints по file:line
- [x] `rider_start_debug` — запуск debugging session с polling
- [x] `rider_debug_evaluate` — evaluate expression в текущем фрейме
- [x] `rider_debug_state` — статус сессии, stack trace с позициями
- [x] `rider_debug_step` — stepOver / stepInto / stepOut / resume / pause / stop

### NuGet
- [x] `rider_list_packages` — пакеты с версиями (dotnet list package), --outdated
- [x] `rider_manage_package` — add/remove через dotnet CLI
- [x] `rider_nuget_restore` — restore с polling

### IDE Settings
- [x] `rider_list_inspections` — поиск инспекций по keyword, фильтр enabled
- [x] `rider_toggle_inspection` — вкл/выкл по shortName
- [~] ~~Generic get/set/list settings~~ — слишком broad, инспекции покрывают основной use case

## P3 — Nice to have

### Terminal Streaming
- [x] Список открытых терминалов (`rider_list_terminals`)
- [x] Отправка input в работающий терминал (`rider_send_terminal_input`)
- [~] ~~Polling session для вывода~~ — stock `execute_terminal_command` покрывает

### Tool Windows — глубокая интеграция
- [x] TODO: все TODO/FIXME/HACK (`rider_get_todos`)
- [x] Endpoints: API маршруты (`rider_get_endpoints`)
- [~] ~~Database~~ — MCP клиент работает с БД нативно
- [~] ~~Services~~ — docker/servers управляются через CLI
- [~] ~~Git Log~~ — `git log` через терминал

### dotTrace Profiling
- [x] `rider_profiling_state` — состояние dotTrace: active session, processes, snapshots, errors
- [x] `rider_profiling_control` — управление сессией: start/stop/drop/detach/close
- [~] ~~`rider_start_profiling`~~ — запуск через Run → Profile в Rider (RD-модель не поддерживает программный запуск без UI)
- [~] ~~Анализ снапшотов~~ — stock `dotTraceGetCallTree/GetTimeline/GetSnapshotInfo` покрывают

### Plugin/Action Management
- [x] `rider_manage_plugin` — list/enable/disable плагинов по id
- [x] `rider_invalidate_caches` — Invalidate Caches & Restart
- [~] ~~`rider_manage_file_watcher`~~ — опциональный плагин, CRUD через CLI/settings файлы

## Технические задачи

- [x] Настроить CI (GitHub Actions) для сборки плагина
- [x] Опубликовать в JetBrains Marketplace
- [x] Протестировать совместимость с Rider 2025.1
- [x] Написать README.md с инструкцией по установке
- [x] Проверить что `build.gradle.kts` собирает с Rider (RD) а не IntelliJ Community (IC)
- [x] Код-ревью + оптимизация токенов (15→12 tools, compact JSON)
