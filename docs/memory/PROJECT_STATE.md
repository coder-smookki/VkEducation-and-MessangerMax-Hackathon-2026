# Текущее состояние КОРНЯ

Обновлено 22.09.2026, Europe/Moscow. Репозиторий `coder-smookki/VkEducation-and-MessangerMax-Hackathon-2026`; интеграционная ветка dev. Проверенная отправная точка runtime - `2f70acf03fbf8383b815a4a528a1df9165f5e51e`. В обновлении 22.09 изменяются документация и проектный дизайн, **не реализуется полный пользовательский продукт**.

## Команда и действующий контекст

Семён - весь Backend Java/Python, SQL и MAX-бот. Кирилл - Frontend, интеграция выпуска. София - независимое тестирование. У всех есть доступ к запуску MAX по сообщению пользователя; это не проверка конкретного сервера. Обязательны полезный текстовый бот и Mini App **https://hackathon.lumnex.ru/**.

Документы синхронизированы с Blueprint21.09 и дизайном 22.09: подробные планы трёх участников, календарь до финала, правила параллельной работы, инструкции Claude/Codex, матрица 62 экранов/API/проверок. Старый персональный план Никиты удалён как активное назначение; история Git сохранена. Текущий навигатор - [docs/README](../README.md).

`design/` содержит редактируемый прототип и 118 основных PNG (62ID), дополнительные viewport/обзоры. Вся палитра адаптирована к официальным исходникам MAX UI; доступный светлый акцент затемнён для контраста. D09/D10 исправлены на архив выбранного пункта. Поля приёма/nextAction, отсутствующие в runtime, описаны как TARGET, а не выданы за работающие.

Внутренний PDF обновлён локально в `output/pdf/KOREN_Blueprint_2026-09-22.pdf`,80 страниц. `output/` исключён из Git: там исходные исследования/закрытые материалы, которые не требуется публиковать для разработки. Агентам достаточно docs/contracts/design. Старый PDF21.09 сохранён как источник.

## Реально реализовано в исходном dev

- Java 21/Spring Boot 4.1.1; React 19.3/TypeScript/Vite8.3; Python 3.12/FastAPI0.141.1; PostgreSQL 17 в Compose. Версии - существующие lock/POM, не обещание обновлений.
- CaseService: внутренние create/get/list/patchSource/patchItems/confirm, транзакции, idempotency/CAS, PostgreSQL-тесты. ItemState/CaseProgress: отдельные состояния и корректный знаменатель.
- MaxInitDataValidator/MaxUserProvisioner и независимые HMAC-векторы. Полноценного HTTP-входа/сессии ещё нет. SecurityConfig закрывает рабочие endpoints.
- Demo React → Java → Python со synthetic case. Это учебный режим, не публичный deployment.
- Пять inference/review контрактов. Extract STUB; clarify RULES по отсутствующим полям; compare STUB по умолчанию и явно включаемый RULES.
- SimilarCasesService отбирает до 20 разрешённых CONFIRMED кандидатов с Membership, вызывает Python вне SQL, затем заново проверяет snapshots/права. Python сравнивает отдельные item pairs.
- KafkaTemplate/конфигурация исправлены ранее; Chroma сохраняет монотонные snapshots,7→6 не откатывает индекс; replay history и честный NOT_EVALUATED. Вызова publisher из CRUD через durable outbox ещё нет.
- Compose/CI/smoke. V1/V2 применялись и не меняются.

## Чего пока нет и что блокирует рабочий продукт

Авторизованный HTTP-путь, invitations/revoked Membership, acceptedAt/By/nextAction в БД/DTO; отчёты/links/observations/history как работающие endpoints; текстовый бот/durable workers/доставка; настоящий UI вместо demo; production-конфигурация и подтверждённый deployment/MAX-проверки.

`reports/observations/notifications/max` пока package-info. Наличие таблиц/контрактов/схемы не реализация сервиса. CaseView не содержит всех будущих visibility/assignment/receipt/action fields. Schema-only REST нужно синхронизировать с фактическими DTO перед включением.

Известный дефект RULES: маркеры смешанных субъектов переносятся между светом и дверью. Пробы 21.09:3 из 6 ошибочных конструкций; это точечная регрессия, не метрика качества. `healthz` сообщает STUB независимо от выбранного compare. S-06/Q-06 должны устранить класс ошибки или обеспечить воздержание и проверить режим. Development precision16/16 не опровергает дефект.

## Проверки и пределы

Исторический [CI19.09 на исходном SHA](https://github.com/coder-smookki/VkEducation-and-MessangerMax-Hackathon-2026/actions/runs/35443265565) - четыре job успешны. Локально 19.09 Java76/0 ошибок/2skip, Python63passed/1skip. Новые runtime-тесты только из-за текста не заявляются пройденными.

22.09: исходный пакет дизайна -221 checksum совпали. Новый render118PNG,32 контрастные пары,22/22 сценария локального прототипа проверены; подробности и актуальная геометрия в [design QA](../../design/qa/qa-report.md). Эти проверки не подтверждают MAX, auth, PostgreSQL и physical devices. Документационные ссылки/task IDs/матрица и PDF проверяются перед коммитом, результаты - [testing](../testing.md).

Следующий шаг: S-01/K-01/Q-01 затем S-02; переиспользовать ядро и согласовать реальные DTO. До выполнения всех Must не объявлять готовый продукт. Персональная карточка Obsidian22.09 не сохранена: внешний том отсутствует; замещающее хранилище не создавалось. Командные файлы здесь сохраняются по прямому запросу пользователя.
