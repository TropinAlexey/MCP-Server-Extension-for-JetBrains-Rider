# ADR-0001: Архитектура безопасности распределённого сервиса

## Статус
**Принято** — 2024-12-19

## Контекст

Мы проектируем распределённый сервис, который должен обеспечивать высокую степень **безопасности** пользователей и данных при операции в враждебной сетевой среде. Угроза исходит от государственных акторов (APT-класс) с возможностями глобального мониторинга, активного зондирования, блокировок инфраструктуры и юридического давления.

Ключевые требования:
- Защита метаданных и контента от корреляционного анализа
- Устойчивость к блокировкам на уровне IP, доменов, протоколов
- Отсутствие единственных точек отказа (SPOF)
- Проверяемая цепочка поставки (supply chain integrity)
- Анонимность операторов и пользователей

## Принятое решение

Мы принимаем многослойную архитектуру **безопасности** на основе следующих принципов:

### 1. Многослойная транспортная архитектура (Defense in Depth)

| Слой | Технологии | Назначение |
|------|------------|------------|
| **Application** | E2E encryption, application-level routing | Защита контента end-to-end |
| **Anonymity** | Tor v3 (primary), I2P (backup), Custom Mixnet (high-value) | Скрытие IP и маршрутов |
| **Obscuration** | VLESS+Reality, TUIC, Snowflake, Domain Fronting | Маскировка трафика под легитимный |
| **Transport** | QUIC (primary), WebSocket, Domain Fronting | Надёжная доставка под цензурой |

**Обоснование:** Никакой единственный протокол не обеспечивает полную устойчивость. Многослойность заставляет атакующего преодолеть несколько независимых барьеров.

### 2. Основной анонимный слой: Tor v3 Hidden Services

- **Версия:** v3 (Ed25519 keys, улучшенная криптография)
- **Изоляция:** `IsolateDestAddr`, `IsolateDestPort` — отдельная цепочка на сессию
- **Плагины:** obfs4 (стабильные мосты), Snowflake (эфемерные WebRTC-мосты)
- **Ротация цепочек:** `NewCircuitPeriod = 30s`

**Почему не VPN/прокси:** Tor обеспечивает onion routing с распределённым доверием, в отличие от централизованных VPN с SPOF.

### 3. Резервный анонимный слой: I2P (Garlic Routing)

- Используется для внутренних коммуникаций между узлами
- Нет exit nodes → устойчиво к correlation attacks внутри сети
- Garlic routing (пакетная доставка) устойчивее к трафиковому анализу

### 4. Высоковажный трафик: Custom Mixnet (3-слойный)

- **Топология:** 3 слоя (entry → middle → exit), адаптивный batch (50-200 сообщений, 50-500ms)
- **Cover traffic:** 10-30% dummy traffic с адаптивным rate
- **ZK-verifiable shuffle:** RISC Zero proofs перемешивания для аудита без раскрытия перестановки
- **PoW для дорогих операций:** reputation-based защита от DoS

### 5. Обфускационный слой (Camouflage)

| Приоритет | Технология | Обнаруживаемость DPI | Сложность |
|-----------|------------|---------------------|-----------|
| 1 | **VLESS + Reality** | Очень низкая (имитирует TLS 1.3) | Средняя |
| 2 | **Snowflake** | Очень низкая (WebRTC) | Средняя |
| 3 | **TUIC / Hysteria2** | Низкая (QUIC noise) | Средняя |
| 4 | Domain Fronting (Cloudflare) | Очень низкая (CDN edge) | Средняя |

**Primary:** VLESS + Reality — выглядит как легаitimate TLS 1.3 handshake для DPI.

### 6. Инфраструктура: Юрисдикционный арбитраж

| Tier | Юрисдикции | Роль |
|------|------------|------|
| **Core** | Швейцария, Исландия, Норвегия | Core nodes, key management |
| **Edge** | Сингапур, Япония, Канада | Entry/exit points, CDN |
| **Ephemeral** | Spot instances (AWS/GCP/Azure) | Snowflake bridges, burst capacity |

**Принцип:** Никаких долговозможных контрактов в рискованных юрисдикциях. Core инфраструктура — только в юрисдикциях с сильными законами о приватности и без mandatory data retention.

### 7. P2P слой: DHT с Sybil Resistance

- **Идентичность:** VRF-based (ECVRF, RFC 9381) — детерминированная, но непредсказуемая
- **Sybil Resistance:** VRF identity + адаптивный PoW + опциональный Web of Trust
- **DHT:** Kademlia (k=20, α=3) с репликацией фактором 6
- **Приватность:** DHT traffic только через mixnet/Tor

### 8. Криптографические примитивы (Post-Quantum Ready)

| Назначение | Алгоритм | Статус |
|------------|----------|--------|
| Key Exchange | X25519 + Kyber-1024 (hybrid) | PQ-ready |
| Signatures | Ed25519 / Dilithium-3 | PQ-ready |
| AEAD | XChaCha20-Poly1305 | Nonce misuse resistant |
| Hashing | BLAKE3 / SHA3-256 | - |
| KDF | Argon2id | Memory-hard |
| VRF | ECVRF (RFC 9381) | Детерминированные ID |
| ZK | RISC Zero / SP1 | General-purpose ZK |

### 9. Supply Chain Security (Обязательно)

- **Reproducible Builds:** Nix + cargo locked deps → бит- идентичные артефакты
- **Signing:** Sigstore/cosign + keyless signing через OIDC
- **SBOM:** SPDX JSON через syft для каждого релиза
- **Verification:** Cosign verification в CI/CD pipeline

### 10. OpSec операторов (Обязательно)

- Анонимные идентичности (псевдонимы, никаких real-name связей)
- Коммуникация только через mixnet/Tor
- Платежи: Monero (XMR) / Zcash (shielded) / Lightning over Tor
- Анонимный хостинг: предоплата криптовалютой, фейковые регистрационные данные
- Air-gapped build machine для релизов
- Canary / Dead Man's Switch с автоматическим триггером раскрытия

---

## Последствия

### Положительные
- ✅ Устойчивость к блокировкам国家级 censorship
- ✅ Защита от трафикового анализа и корреляции
- ✅ Отсутствие SPOF в инфраструктуре
- ✅ Проверяемая цепочка поставки
- ✅ Post-quantum readiness
- ✅ Анонимность операторов по умолчанию

### Отрицательные / Риски
- ⚠️ **Сложность:** Многослойность увеличивает surface area для багов
- ⚠️ **Latency:** 3-5 hopов через mixnet + Tor = 200-800ms RTT
- ⚠️ **Throughput:** Cover traffic + padding = 30-50% overhead
- ⚠️ **Operational Complexity:** Требует высококвалифицированной команды OpSec
- ⚠️ **Tor Dependency:** Деградация Tor сети влияет на доступность (митигация: I2P + Mixnet fallback)

### Операционные затраты
- **Инфраструктура:** ~$120k/год (50+ нод глобально)
- ✅ Команда: 5 инженеров (Rust/crypto/OpSec)
- ✅ Аудиты: 2 аудита/год + $100k bug bounty
- ✅ Legal: юрисдикционный консалтинг

---

## План внедрения (Phased Approach)

| Фаза | Срок | Deliverables |
|------|------|--------------|
| **Phase 1: Foundation** | M1-M3 | Core mixnet node, Tor/I2P lib, Sphinx packets, reproducible builds |
| **Phase 2: Anonymity Layers** | M3-M6 | Tor HS, I2P, VLESS+Reality, Snowflake, Domain Fronting |
| **Phase 3: Mixnet + P2P** | M6-M12 | Custom mixnet, ZK-shuffle, VRF-DHT, Service Discovery |
| **Phase 4: App Services** | M12-M18 | Encrypted messaging, storage, private compute, anon payments |
| **Phase 5: Hardening** | M18-M24 | Formal verification, 2 аудита, bug bounty, red team, open source |

---

## Альтернативы, которые рассмотрели и отклонили

| Альтернатива | Причина отказа |
|--------------|----------------|
| **WireGuard / VPN** | Централизованный контроль, SPOF, нет onion routing |
| **Только Tor** | Single point of failure, уязвим к correlation attacks без cover traffic |
| **Централизованные CDN (Cloudflare Workers)** | Жесткая зависимость от одного провайдера, юридическое давление |
| **Blockchain-based routing** | Слишком высокая latency, дорогая, не нужна для routing |
| **Только обфускация (VLESS/Shadowsocks)** | Не защищает от correlation attacks, нет анонимности маршрута |

---

## Метрики успеха (KPIs)

| Метрика | Target | Measurement |
|---------|--------|-------------|
| **Availability** | >99.5% | Uptime monitoring через мультирегиональные пробы |
| **Censorship Resistance** | 100% bypass | Тестирование в GFW, Roskomnadzor, Great Firewall тестовых стендах |
| **Latency (P95)** | <800ms | End-to-end через 3-hop mixnet + Tor |
| **Cover Traffic Ratio** | 15-30% | Адаптивный dummy rate |
| **Build Reproducibility** | 100% | Два последовательных билда = бит- идентичные артефакты |
| **Audit Findings (Critical/High)** | 0 | Внешние аудиты 2x/год |

---

## Связанные ADR

- **ADR-0002:** Выбор криптографических примитивов (Post-Quantum)
- **ADR-0003:** Mixnet topology & ZK-shuffle design
- **ADR-0004:** VRF-based DHT & Sybil Resistance
- **ADR-0005:** Supply Chain Security & Reproducible Builds

---

## Примечания

Это ADR является **стратегическим** и требует согласования архитектурного комитета. Любые изменения в транспортных слоях или криптографических примитивах требуют нового ADR с обоснованием.

**Авторы:** Архитектурное комите  
**Рецензенты:** Security Team, Infra Team, Legal  
**Следующий ревью:** 2025-03-19 (через квартал)
