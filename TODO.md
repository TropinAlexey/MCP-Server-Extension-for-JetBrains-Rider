# MCP Server Extension — Roadmap

## Выполнено (архив)

P0–P3 закрыты полностью, релизы v1.0.6–v1.1.0 в маркете. Что есть: 41 тул
`rider_*` — сборка с polling, процессы, IDE-state/нотификации, чтение
любых tool windows (табы, секции, пагинация), контекст программиста,
тесты (run/wait/дерево/rerun/filter, запуск без конфигов), run-конфиги CRUD,
.NET-отладчик, NuGet, инспекции, терминалы, TODO/Endpoints/DB-консоли
(включая выполнение SQL), dotTrace/dotMemory, invalidate caches.
Плюс: CI, Marketplace, README в пользовательском виде, troubleshooting,
issues-шаблоны, verifyPlugin без ошибок.

Отдельные исторические решения (не reopen без веса):
- Publish/MSBuild verbosity — нет VS-style publish в Rider
- Подписка на уведомления — покрыта polling `rider_get_notifications`
- Generic settings API — инспекции покрывают use case
- `rider_manage_plugin` — удалён (API `@Internal`)
- `rider_start_profiling` — RD-модель не даёт программного запуска
- Анализ снапшотов — покрывает stock dotTrace-инструментарий
- Polling вывода терминалов — покрывает stock `execute_terminal_command`

## Активно: P4 — Стратегия (из разбора mcp-steroid, 09.2026)

Принцип: глубина в нише Rider/.NET + их дисциплина, без их затрат.
Детали — `notes/rider-mcp-vs-mcp-steroid.md`.

### Дисциплина поверхности (рецепты > тулы)
- [x] Мораторий формализован: `CONTRACT.md` + правила в `CLAUDE.md`
  (новый тул — только если рецепт не покрывает + причина в коммите +
  дельта эвалов не регрессирует)
- [x] Аудит слияния первой пары: `rider_run_tests` / `rider_run_tests_and_wait`
  — KEEP BOTH, rationale в `CONTRACT.md` (разные нужды: poll vs один вызов)
- [ ] Продолжать cookbook-описания: цепочки и рецепты в `McpDescription`
  (тесты, live-debug, Console-секции — уже есть, расширять по затыкам)

### Замеры вместо вкуса (мини-эвалы)
- [x] Фиксированный набор сценариев — `notes/evals/scenarios.md` (S1–S11)
- [x] Метрики прогона — `notes/evals/methodology.md` (только machine-checkable)
- [x] Снять baseline-раунд в живой связке Rider+агент, лог — `notes/evals/results-log.md`
  (снято дважды: R1 в одном чате + R2 10 холодных сессий; дальше — дельты по правилу)
- [ ] Прогонять до/после каждого изменения тулов; решения — по дельте

### Стабильность контракта
- [x] Additive-only зафиксирован: `CONTRACT.md` (поля только добавляются;
  новый тул предпочтительнее смены shape; breaking — только с мажором)
- [ ] Держать: сверять ответы тулов с `CONTRACT.md` перед каждым релизом

### Ниша Rider/.NET (углублять, не шириться)
- [x] DB-консоли: выполнение (feedback 19.09) — `rider_execute_console` реализован
  (сборка+verify зелёные; живая проверка при релизе). Идёт через IDE-пайплайн
  без требования интроспекции; `ai`-модуля нет на compile-classpath 261 —
  доступ через рефлексию с graceful-деградацией. Сценарий S10 ждёт тул —
  теперь тоже разблокирован
- [ ] Upstream закрыт обходами без тикетов (feedback 19.09): launchpad-база +
  `sys.databases` (№3+6), правило путей (№5), do-not-use `execute_tool` (№7),
  №4 — ложная тревога (ручная отмена, штатно). Зафиксировано в cookbook,
  README-troubleshooting и S11. Пересмотреть только если обходы упираются
- [x] dotMemory: `rider_memory_state` / `rider_memory_control` по образцу
  dotTrace-тулов (реализовано, сборка+verify зелёные; живая проверка и
  бамп версии — при релизе). Сток покрывает только анализ
  dotTrace-снапшотов — дубля нет. Нюанс: сгенерированного
  `solution.dotMemoryHost` нет в модели 2026.1, хост резолвится вручную
  через `getOrCreateExtension` с graceful-деградацией
- [ ] Сценарии поверх .NET-специфики: docker/Services-панель,
  Publish-профили, покрытие кода (dotCover)
- [ ] НЕ делать: свой сервер/CLI, execute_code-аналог, UI-автоматизацию,
  поддержку других IDE

### Видимость (bus factor и спрос)
- [ ] Junie: проверить связку Rider + Junie + MCP-сервер — протокол
  `notes/evals/junie-check.md`, сценарий S9. Отдельной разработки не надо,
  только верификация + 3 строки в README со ссылкой на доки JetBrains
- [ ] Скриншоты/демо в README и на маркет (пусто до сих пор)
- [x] Issues-шаблоны заведены: `.github/ISSUE_TEMPLATE/` (bug + feature
  с полем «почему не покрывается рецептом», отсылка к CONTRACT.md)
- [x] Discussions: решено не включать — при нулевом потоке пустой раздел
  хуже его отсутствия (`has_discussions: false`, проверено 21.09 через API).
  Вопросы живут в issues с лейблом `question`; вернуться к Discussions
  при первых 3–5 вопросах от живых людей.
  Открытый остаток: 0 issues — нормально или никто не ставит?
- [ ] Сверить счётчики в README с кодом перед каждым релизом
  (41 тул, Rider 2026.1+)

## P5 — Цикл и рецепты (по мотивам steroid-стратегии, 09.2026)

Контрпозиция: лучший attended-Phase-1 для Rider, не headless-рантайм.
Сильные стороны забираем, затраты — нет. Разбор — в обсуждении
стратегии devrig (strategy-page, three-phase arc).

### Skill Factory в миниатюре
- [ ] Рецепты — версионируемые ассеты, а не строки в `McpDescription`:
  отдельное место, ревью, тесты на дрейф (сейчас потолок — описания)
- [ ] Цикл «агент фиксирует затык → предлагает рецепт → рецепт ложится
  в тул и в мини-эвал» — self-improvement loop на наших сессиях
- [ ] Затыки собирать локально и приватно (сессии не покидают машину —
  в пику их `~/.mcp-steroid/runs`)

### Честные замеры
- [ ] Метрики только machine-checkable (вызовы, токены, wall time,
  успех с 1-й попытки) — без «агент сказал, что всё хорошо»
- [ ] Публиковать и проигрыши IDE-трека: где shell оказался лучше,
  фиксировать как known limits, а не прятать

### Enterprise-дружелюбие как дифференциатор
- [ ] Держать поверхность фиксированной: никакого execute_code-аналога
  (RCE в IDE энтерпрайз не пустит — нас пустит)
- [ ] Privacy-гарантии явно в README: что тулзы читают/меняют, что никуда
  не отправляется (уже частично в Limitations — расширить и держать)
- [ ] Brave mode выключен по умолчанию — так и оставить, задокументировать
  как фичу безопасности, а не как неудобство

## P6 — Cross-IDE & Ecosystem (New, 10.2026)

### Universal IDE support (DONE: Rider, RustRover, PyCharm, GoLand, WebStorm, IntelliJ IDEA, all IntelliJ-based)
- [x] Plugin loads in all JetBrains IDEs 2026.2+ (IntelliJ IDEA, PyCharm, WebStorm, GoLand, CLion, DataGrip, PhpStorm, RubyMine, Android Studio, etc.)
- [x] Rider-only tools isolated via `rider-only.xml` (NuGet, Publish, SWEA, dotTrace/dotMemory, WorkspaceConfig)
- [x] RustRover-only tool: `rider_cargo` (Cargo package management) via `rust-only.xml`
- [x] PyCharm-only tools: `rider_python_env`, `rider_pip`, `rider_pytest`, `rider_django` via `python-only.xml`
- [x] GoLand-only tools: `rider_go_mod`, `rider_go_test`, `rider_gopls` via `go-only.xml`
- [x] WebStorm-only tools: `rider_npm`, `rider_js_test`, `rider_eslint` via `js-only.xml`
- [x] IntelliJ IDEA-only tools: `rider_maven`, `rider_gradle`, `rider_junit` via `java-only.xml`
- [ ] Add IDE-specific tools per platform:
  - C++ (CLion): CMake, Conan, GDB/LLDB
  - PHP (PhpStorm): Composer, PHPUnit, Psalm/PHPStan
  - Ruby (RubyMine): Bundler, RSpec, RuboCop
- [ ] Document IDE-specific tool availability in a generated matrix (auto-sync from code)

### Cargo tool enhancements (RustRover)
- [x] `rider_cargo`: support `cargo test` integration (parallel with `rider_tests`)
- [x] `rider_cargo`: `cargo check` / `cargo clippy` / `cargo fmt` actions
- [ ] `rider_cargo`: workspace member filtering, target filtering
- [ ] `rider_cargo`: registry/auth management (crates.io tokens, private registries)

### Cross-IDE workflows
- [ ] Universal `rider_workspace_config` that works per-IDE (build output paths, run configs)
- [ ] Universal test runner abstraction over IDE's native test frameworks
- [ ] Universal database tooling (already done via JDBC — works everywhere)

### Marketplace & Distribution
- [ ] Screenshots/GIFs in README and Marketplace listing
- [ ] Automated changelog generation from conventional commits
- [ ] Separate versioning track for RustRover-specific features if needed
- [ ] Verify Plugin Verifier on all target IDEs (Rider, RustRover, PyCharm Community)

### Quality & Testing
- [ ] Add integration tests for `rider_cargo` (mock cargo or use test fixture)
- [ ] CI: add RustRover verification on Linux (if artifacts become available)
- [ ] CI: IntelliJ IDEA Community verification — deprecated in 2025.3, skipped
- [ ] Mutation testing for critical paths (DB read-only guard, secret masking)
- [ ] Property-based testing for SQL parsing (GO splitting, semicolon handling)
