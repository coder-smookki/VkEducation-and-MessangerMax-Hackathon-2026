# Что проверено в похожих решениях

Проверка: 12.09.2026, Europe/Moscow. Изучены первоисточники и отдельные файлы GitHub. Это ограниченное сравнение архитектур, не полный аудит рынка и не доказательство абсолютной новизны. Чужой код в каркас не копировался.

## Прикладные выводы

| Источник | Что действительно найдено | Решение для КОРНЯ |
|---|---|---|
| FixMyStreet, mySociety | В `Problem.pm` различаются `fixed - user`, `fixed - council`, открытые, исправленные и закрытые состояния | Сохранить источник утверждения. В нашей модели дополнительно разделить его по пунктам, редакциям и раундам |
| Open311 GeoReport v2 | Стандартная service request имеет status open/closed и status_notes | Будущий Open311-коннектор хранит внешний статус отдельно; не превращает closed в подтверждение автором всех пунктов |
| Официальный MAX TypeScript SDK | Webhook проверяет секрет constant-time; standalone handler отвечает 200 перед `handleUpdate` | Использовать протокол и проверки. В Java добавить durable inbox до ACK, чтобы падение после ответа не потеряло команду |
| Официальный MAX UI | npm 0.4.0 и прочитанный package.json требуют React/React DOM 19.2.8 как точные peerDependencies | Не применять `--force`/`--legacy-peer-deps`. Стартовый UI использует native HTML + React; при подключении MAX UI согласовать версии и проверить сборку |
| doccano | Инструмент для совместной разметки текстовой классификации и выделения последовательностей | Не писать собственную платформу разметки. Начать с JSONL; если ручная совместная разметка тормозит, использовать отдельный локальный doccano |
| mDeBERTa NLI | Карточка готовой multilingual NLI-модели, MIT, примеры применения | Это кандидат для N2; качество на ЖКХ и скорость требуют собственного измерения, веса пока не запускались |

## Закреплённые исходники

- FixMyStreet: SHA `d587914990960be5979409b1c7bf0e576053a9f4`; [Problem.pm](https://github.com/mysociety/fixmystreet/blob/d587914990960be5979409b1c7bf0e576053a9f4/perllib/FixMyStreet/DB/Result/Problem.pm), методы `open_states`, `fixed_states`, `closed_states`; [LICENSE.txt](https://github.com/mysociety/fixmystreet/blob/d587914990960be5979409b1c7bf0e576053a9f4/LICENSE.txt) указывает AGPL-3.0-or-later по умолчанию. Это ориентир поведения, не Java-основа проекта.
- MAX SDK: SHA `963ba4aba049f9b16bd2c254f840f016ea96bdd9`; [webhook.ts](https://github.com/max-messenger/max-bot-api-client-ts/blob/963ba4aba049f9b16bd2c254f840f016ea96bdd9/src/core/network/webhook.ts), методы `createCallback` и `isSecretValid`. Наблюдение об ACK относится именно к этому standalone handler; оно не означает ненадёжность всех решений на SDK.
- MAX UI: SHA `106d6302d0dd05f092b987fae881164c71412bd5`; [package.json](https://github.com/max-messenger/max-ui/blob/106d6302d0dd05f092b987fae881164c71412bd5/package.json). Текущий `npm view @maxhub/max-ui version peerDependencies --json` также вернул 0.4.0 и React 19.2.8. Поисковый индекс показывал другие версии, поэтому решение принято по реальному package.json/npm.
- doccano: SHA `ab6b765ea326d07b2b806a4280cd097565e62321`; [README](https://github.com/doccano/doccano/blob/ab6b765ea326d07b2b806a4280cd097565e62321/README.md). Инструмент не установлен в основной runtime КОРНЯ.
- [Open311 GeoReport v2](https://wiki.open311.org/GeoReport_v2/) - спецификация полей service request.
- [Карточка NLI](https://huggingface.co/MoritzLaurer/mDeBERTa-v3-base-mnli-xnli) - основание выбора кандидата, не наши результаты.

## Что улучшает решение в рамках нашей задачи

1. **Не потерять пункт.** Контракт требует отдельный результат для каждого item, даже при abstention. Java проверяет полноту и уникальность IDs.
2. **Не подтвердить старую работу.** contentVersion и workRound входят в предложения и наблюдения. Новый раунд снимает актуальность прежнего наблюдения.
3. **Не выдумать доказательство.** Evidence проверяется на точное совпадение, включая emoji/Unicode. NLI не присваивает рабочий статус.
4. **Не потерять сообщение при падении.** План inbox/outbox основан на durable записи до сетевых подтверждений, с идемпотентной обработкой.
5. **Не скрыть отсутствие качества.** STUB и недоступная модель различаются. В eval precision отсутствует при нуле предложений, coverage публикуется рядом.
6. **Не усложнить запуск.** Java остаётся основой, Python имеет одну чёткую функцию. Нет стороннего workflow engine, брокера и собственной системы разметки.

Пункты 1-3 и разделение STUB/MANUAL уже закреплены в каркасе и тестах. Workers, полноценная авторизация и измерения качества находятся в планах. Преимущество по времени разбора должно быть показано экспериментом A/B/C; факт наличия архитектурных ограничений сам по себе не доказывает улучшения UX или модели.

## Актуальные технические источники

Context7 использован для Spring Boot 4.1, Spring Security, PostgreSQL 17, React, Vite, FastAPI и Docker Compose. Сборка backend получена из Spring Initializr; фактические версии подтверждены Maven Central/npm/PyPI и lock-файлами.

- [Spring Boot: первое приложение](https://docs.spring.io/spring-boot/tutorial/first-application/index.html).
- [Spring Security: авторизация запросов](https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html).
- [PostgreSQL 17: ограничения](https://www.postgresql.org/docs/17/ddl-constraints.html).
- [FastAPI: тестирование](https://fastapi.tiangolo.com/tutorial/testing/).
- [Vite: server.proxy](https://vite.dev/config/server-options.html#server-proxy).
- [MAX: валидация](https://dev.max.ru/docs/webapps/validation), [Bridge](https://dev.max.ru/docs/webapps/bridge), [подписка webhook](https://dev.max.ru/docs-api/methods/POST/subscriptions).

Страница MAX от 12.09.2026 указывает новый API-host `platform-api2.max.ru` и требования TLS. Сохранять старые параметры из PDF без проверки нельзя. Найденный через поиск прежний адрес официального Java SDK вернул 404; это не доказательство отсутствия всех Java-библиотек. Для нескольких нужных операций достаточно прямого HTTP из Java по официальному протоколу.

## Исходный отчёт

`KOREN_MAX_Smart_City_Report_v2_2026-09-06.pdf`, 33 страницы; SHA-256 `ba5cc8ac3263aaa158dd96537e3f0746161fa1fef934c01e50ef771e1ae80448`. Прочитана проектная часть; архитектурные страницы 17-18 дополнительно просмотрены после рендеринга. PDF не копируется в Git; его существенные требования изложены в product/domain/architecture.
