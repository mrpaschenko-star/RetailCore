# Cutover: внутреннее чтение истории заказа

## Цель и область

Переключить согласованный экран поддержки для выбранной когорты с legacy read-path на Support History Read API, сохранив создание/изменение заказа, деньги, резервы, ручные actions и клиентский API в старом контуре. Основа — [T03](../Task3/transformation.md). Это проект операционного плана: фактические endpoint, feature-switch mechanism, runtime-команды и численные пороги неизвестны; их заполнение и репетиция — обязательный entry gate G16.

Единица переключения — неизменяемая версия route configuration для **одного согласованного экрана/роли/когорты**. В ней задаются scope, legacy route, new route, contract/mapping versions и degraded behavior. Конкретные имена конфигурации ниже являются проектными обозначениями, не существующими production-флагами.

## 1. Параметры, без которых запуск запрещён

| Параметр | Что нужно заполнить | Кто утверждает / пробел |
|---|---|---|
| `SCOPE`, `C_canary` | Экран, роли, каналы, заказы/период, критерии стабильного выбора когорты, исключённые операции | Support + заказы; G12/G16 |
| `SOURCE_SET`, `M_version` | Полный для scope перечень writers/tables/aspects, mapping/контракт, источники paid/refunded и правила unknown | Заказы + DBA + финансы/логистика; G04/G13 |
| `L_max`, `STALE_POLICY` | Допустимая свежесть по аспектам, обработка incomplete/stale/unavailable, отсутствие заказа в scope | Support + бизнес; G05/G12 |
| `W_compare`, `W_observe`, `CASE_SET` | Окна shadow и после включения, набор normal/exception/channel cases и правило достаточности выборки | Заказы + QA + support; G05 |
| `E_sem`, `E_api`, `P_read` | Допуски семантических расхождений, ошибок и latency чтения; что немедленно блокирует rollout | Support + эксплуатация + владельцы фактов; G01/G03/G04 |
| `B_oracle`, `B_new`, `F_capacity` | Guardrails Oracle/нового контура, ограничения backfill/shadow, допустимая возвращаемая нагрузка | DBA + эксплуатация; G02/G03/G14 |
| `RPO_scope`, `RTO_scope`, `T_rollback` | Потеря/время восстановления для scope и отказов; измеренный срок возврата маршрута | Эксплуатация + DBA + бизнес; G06 |
| `RETENTION`, `ACCESS` | Хранение журналов/сверки, разрешённые поля и роли, аудит | ИБ + владельцы данных; G10 |
| `RUNBOOK_VERSION` | Проверенные endpoints, команды switch/readback/revert, поведение caches/sessions/inflight, роли доступа и контакты | UI/заказы + эксплуатация; G16 |

Пока хотя бы обязательное поле не заполнено, решение **NO-GO**. Нет значения canary по умолчанию, «типового» retry/timeout или автоматического переноса SLO из AdScale. Критические логические запреты независимо от чисел: нельзя подтверждать paid без источника, раскрывать запрещённые данные, выполнять команды из read API или скрывать gap capture.

## 2. Роли и полномочия

| Роль | Ответственность |
|---|---|
| Координатор переключения (назначает IT) | Ведёт change record, собирает GO/NO-GO, исполняет stop/rollback по runbook, фиксирует время и route version |
| Support owner | Принимает screen/когорту, объяснение состояния и degraded policy, обеспечивает сценарную проверку оператором |
| Команда заказов / UI | Владеет маршрутизацией, старым и новым API-контрактом, гарантирует неизменность write-actions |
| DBA / capture owner | Проверяет snapshot, логи, транзакции, writer coverage, checkpoints и Oracle guardrails |
| Projector / read API owner | Mapping, idempotency, schema versions, quarantine и replay, диагностика расхождений |
| Эксплуатация | Capacity, dashboards/alerts, backup/restore, доступность rollback и наблюдение нагрузки |
| QA + финансы/логистика | Golden cases, сверка аспектов с authoritative facts, классификация расхождений |
| ИБ | Допустимость копирования, ACL/PII, retention и audit |

Любой владелец может объявить safety stop по своей зоне. Координатор не ждёт бизнес-комитета для заранее согласованного аварийного возврата. Расширение scope требует повторного решения ответственных за новый scope; внешний партнёр не получает новых write-вызовов в этом cutover.

## 3. Entry gates

| Gate | Проверяемое доказательство |
|---|---|
| CG1. Scope/semantics | Подписаны SCOPE, SOURCE_SET, M_version, CASE_SET и unknown policy; ручные/ночные writers включены либо scope явно исключает их |
| CG2. Данные | Snapshot manifest с consistent position S, capture covers long-running transactions, snapshot completeness, непрерывный change range и durable checkpoints; gaps отсутствуют для принятого scope |
| CG3. Поведение | Shadow comparison на согласованном окне пройдена; mismatch объяснены/исправлены; нет скрытого превращения payment session в paid |
| CG4. Нагрузка | Backfill/shadow ограничены, B_oracle/B_new/F_capacity измерены; legacy выдерживает согласованный возврат; обычные продажи не деградируют |
| CG5. Отказ и восстановление | Проверены остановка capture/projector/store/API, duplicate/reorder/schema mismatch, rebuild/restore и route rollback; измерения укладываются в утверждённые RPO/RTO |
| CG6. Доступ и эксплуатация | ACL/PII/retention/audit проверены, владельцы/on-call доступны, dashboards и signal freshness работоспособны |
| CG7. Переключатель | RUNBOOK_VERSION проверен на staging, маршрутизация ограничена чтением, old/new contracts совместимы, old artifact/config сохранены, config readback и cache/inflight semantics известны |

Календарное наступление R4 не заменяет CG1–CG7. Неполный historical scope должен быть принят явно: snapshot показывает состояние на S, не все предшествовавшие ему переходы.

## 4. Пошаговый сценарий

| Шаг | Действие / исполнитель | Состояние данных и проверка | Условие продолжения |
|---|---|---|---|
| 1. Зафиксировать baseline | Координатор сохраняет route/config/API/mapping versions, границы scope, текущую нагрузку и GO всех владельцев | Штатное чтение legacy, источник записей Oracle; нет active конкурирующего изменения capture schema | CG1–CG7 выполнены, thresholds заполнены |
| 2. Подготовить данные | DBA проверяет bootstrap manifest и логовую непрерывность; при первом запуске делает bounded snapshot/replay по Task3 | Capture стартует с достаточной позиции до S, учитывает незавершённые транзакции; snapshot на S + committed changes после S | Нет gaps, snapshot сверен, SOURCE_SET покрыт, B_oracle соблюдён |
| 3. Проверить догон | Capture/projector owners сравнивают source heartbeat/committed position, durable journal position и projected position | Checkpoint применяется атомарно с read-model; lag измеряется end-to-end, ошибки/quarantine видны | Freshness/coverage соответствуют утверждённым правилам |
| 4. Shadow-read | QA сравнивает старый и новый ответ на CASE_SET с ограниченной нагрузкой; новые ответы ещё не показываются как штатные | Сверяется один scope и сопоставимый checkpoint; reads не вызывают PSP/reserve/notification/retry | W_compare и критерии достаточности выполнены; mismatch разобраны |
| 5. Проверить оператора и возврат | Support подтверждает UI на representative cases, эксплуатация проверяет доступность legacy route и F_capacity | Actions вызывают прежний handler с проверкой актуального state; known/unknown/stale видны | Нет команды через новый read-store, rollback готов |
| 6. Включить canary | Координатор применяет новую route version для C_canary и читает конфигурацию обратно со всех routing nodes | Только выбранные GET идут в новый API. Остальные reads и все writes остаются прежними | Логи показывают ожидаемую когорту/route version, нет утечки scope |
| 7. Наблюдать | Эксплуатация + support следят за W_observe; QA продолжает bounded сравнение | Проверяются показатели ниже, свежесть источника, coverage и реальный outcome обращения; отсутствие alerts не заменяет проверку | Пороги соблюдены, исключения объяснены, нет stop-trigger |
| 8. Принять либо вернуть | Координатор фиксирует evidence; support/orders/ops принимают scope или запускают rollback | Journal/checkpoints/version manifest сохранены; Oracle owner не меняется | Подписан результат. Расширение только после нового scope gate |

Отсутствие соответствующего exception-case в живом canary не считается его успешной проверкой. Его воспроизводят на безопасном стенде с test doubles/обезличенными данными; coverage и недостаточная статистика явно отмечаются до решения.

## 5. Сверка поведения и наблюдаемость

Старый экран — объект сравнения, но не единственный эталон истины: он сам может неоднозначно трактовать состояние. QA фиксирует source order ID, aspect, версии, source checkpoint/time boundary, оба ответа и authoritative evidence. Если legacy read не умеет чтение на checkpoint, сравнение выполняется на стабильном case после подтверждённого догоняния или на воспроизводимом replay; несопоставимые снимки не объявляются ни совпадением, ни дефектом.

Классы расхождений: ожидаемый согласованный lag; mapping error; missing source/writer; разные определения; неподдержанный scope; дефект legacy; unknown requiring owner. Принятый lag должен лежать в L_max, остальные блокирующие классы определяются в E_sem. Отложенный callback не получает выдуманный результат для «совпадения» ответов.

| Наблюдение | Что сопоставлять / решение |
|---|---|
| Бизнес-исходы | Normal, manual review, delivery pending, payment pending, paid/refund, split/partial cancel; источники по аспектам, известный следующий шаг и причины |
| Полнота | Snapshot counts/keys/checksums на S в пределах scope, изменения/удаления после S, все writers, начало доступной истории и unknown |
| Свежесть | Source heartbeat/commit → durable → projected → served checkpoint; clock skew и остановившийся поток видны отдельно |
| API/UI | Ошибки, распределение latency и объём на route/cohort/version; отсутствие заказа в проекции не скрывается как authoritative not-found |
| Production | Checkout/payment business success, Oracle waits/IO/pool, overhead capture/shadow, capacity legacy и нового store |
| Безопасность/операции | ACL, redaction PII, audit access, все write-actions остаются прежними; operator cases и stop-events фиксируются |

Счётчики новых и legacy reads маркируются без PII; order ID не используется как высококардинальный metric label. Подробные case-ссылки доступны в защищённом журнале по G10. Alerts привязаны к owner и действиям: stop cohort expansion, pause backfill, rollback route, rebuild projection. Численные thresholds отсутствуют во входных данных и заполняются до CG gates.

## 6. Stop и rollback

Немедленный stop: нарушение доступа/PII, новый write side effect, неподтверждённый paid/delivery факт, gap source, выход маршрута за scope. Остальные причины: превышены согласованные E_sem/E_api/P_read/L_max/B_oracle/B_new, потеря мониторинга, невозможность сохранять принятый degraded mode или восстановления в T_rollback. Владелец объявляет stop и координатор прекращает расширение.

1. Сохранить config version, checkpoints, case IDs, значения метрик и причину; сбор evidence не должен задерживать аварийный возврат. При проблеме ACL немедленно закрыть доступ к новому API по согласованному security runbook.
2. Проверить F_capacity и состояние legacy. Если старое чтение выдерживает scope, применить сохранённую route version и прочитать конфигурацию обратно на всех узлах. Если не выдерживает, включить заранее согласованный ограниченный degraded mode/ограничить чтение; нельзя устроить перегрузку Oracle массовым fallback. Эта развилка репетируется до GO.
3. Обработать cache/session/inflight по RUNBOOK_VERSION: новые запросы маршрутизируются в legacy, старые read-ответы больше не показываются как новые после смены версии. Конкретные invalidate/drain команды и срок обязаны быть проверены; нельзя считать запись одного флага мгновенным завершением отката.
4. Support и QA повторяют критичные cases на legacy, проверяют доступность прежних actions. Эксплуатация подтверждает route distribution, Oracle guardrail и отсутствие зависимости checkout от нового контура. Зафиксировать измеренное время возврата и сравнить с T_rollback.
5. Если capture/backfill стал причиной перегрузки, приостановить его и отметить risk log-retention/gap. Если capture безопасен, можно оставить journal для расследования при действующих ACL. Faulty projector остановить, не выдавать его результаты. Удержание evidence только по RETENTION.
6. Исправить mapping/capture/API, при необходимости перестроить производную модель из корректного checkpoint/snapshot; повторить CG2–CG7 и новый GO. Не «перескочить» через потерянный участок лога.

Rollback **не восстанавливает Oracle из backup и не откатывает заказы/оплаты**: cutover не менял write owner. Не удаляются source records и не отправляются компенсации партнёрам. Если ошибочный read повлиял на решение оператора, кейсы из audit разбираются отдельно владельцем operations; возврат маршрута не отменяет уже выполненное действие.

## 7. Закрытие перехода

В итоговом change record: фактический scope и версии, заполненные параметры, результаты gates/shadow/canary/rollback rehearsal, все принятые расхождения, ограничения истории, ответственные за поддержку и следующий gate. Старый reader остаётся доступным на согласованный период/до снятия зависимостей; его удаление — отдельное изменение. Без production-доступа в этой работе подготовлен план, но не утверждается, что переключение или измерения выполнены.
