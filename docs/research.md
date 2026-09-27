# Исследование и проверенные основания

Это краткая открытая часть обоснования, не копия закрытых материалов конкурса. Подробный внутренний Blueprint и исходники находятся у команды локально в output/pdf и не публикуются автоматически. Даты исходного исследования - 21-22.09.2026, профиль MAX дополнительно проверен 27.09; перед интеграцией внешнего API сверять актуальную первичную документацию.

## Продуктовые ориентиры

- [Госуслуги Дом: обращения](https://cdn.dom.gosuslugi.ru/webhelp/topics/mobapp/requests/c_requests_main-miniapp.html) - существующие обращения; нельзя заявлять, что сама подача сообщения новая.
- [Домиленд](https://domyland.ru/) и [Бурмистр: MAX-бот](https://burmistr.ru/services/chat-bot-v-max/) - рынок жилищных сервисов и интеграций уже существует. Маркетинговые заявления поставщиков не независимые измерения.
- [FixMyStreet: административные сценарии](https://fixmystreet.org/pro-manual/admin-tasks/) - работа с обращениями/дубликатами. [Код v6.0](https://github.com/mysociety/fixmystreet/blob/v6.0/perllib/FixMyStreet/DB/Result/Problem.pm) использован как выборочный ориентир источников состояния; проект не запускался и не переносится целиком.
- [Atlas CMMS WorkOrder v1.8.0](https://github.com/Grashjs/cmms/blob/v1.8.0/api/src/main/java/com/grash/model/WorkOrder.java) - ориентир Java-модели рабочего задания. Не готовая замена нашей модели пункта/наблюдения.

Проверяемое преимущество КОРНЯ - непотерянный остаток по отдельным пунктам, авторское наблюдение отдельно от отчёта и следующий ответственный шаг в двух каналах. Уникальность на всём рынке и экономию времени без исследования не утверждать.

## MAX и дизайн

[Mini App](https://dev.max.ru/docs/webapps/introduction), [Bridge](https://dev.max.ru/docs/webapps/bridge), [валидация initData](https://dev.max.ru/docs/webapps/validation), [Update](https://dev.max.ru/docs-api/objects/Update), [подписки webhook](https://dev.max.ru/docs-api/methods/POST/subscriptions), [отправка сообщений](https://dev.max.ru/docs-api/methods/POST/messages).

22.09 дополнительно прочитаны [MAX UI](https://dev.max.ru/ui) через Context7 и Git `max-messenger/max-ui`, SHA `1df07bd7c740374cd40612c07654c3fade7c2e9f`. Цвета взяты из [appearance.scss](https://github.com/max-messenger/max-ui/blob/1df07bd7c740374cd40612c07654c3fade7c2e9f/src/styles/variables/appearance.scss), системный шрифт из common.scss. Exact peers этого source package расходятся с React проекта; библиотека не установлена принудительно. Принято использовать проверенные цветовые роли и простые CSS-компоненты. Подробности/контраст - [design.md](design.md).

27.09 официальные Bridge/validation дополнительно сверены для профиля: поля `first_name`, `last_name`, `username`, `photo_url` используются только после проверки подписанного initData; optional username/photo могут отсутствовать. В текущем MAX Web подтверждена загрузка аватара с `https://i.oneme.ru`; источник явно разрешён в CSP изображений, wildcard для всех HTTPS не добавлен. Это проверка текущего источника, не обещание неизменности CDN. Детали безопасности и доказательство - [QA профиля](qa/profile-visual-2026-09-27.md).

Оформление ДомПульса 27.09 использует переданные пользователем assets/boards и собственные компоненты в цветовых ролях MAX. Это не официальное одобрение бренда MAX.

Первичный официальный источник подтверждает UI-систему и библиотеку, но это не полученное индивидуальное одобрение полного маркетингового брендбука. Не переносить произвольные картинки из поиска и не путать MAX с одноимёнными продуктами.

## Доступность и честные измерения

[Контраст](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html), [использование цвета](https://www.w3.org/WAI/WCAG22/Understanding/use-of-color.html), [увеличение текста](https://www.w3.org/WAI/WCAG22/Understanding/resize-text.html), [размер цели](https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum.html). В проекте выбран touch minimum44, основной CTA52 и 200% проверка без потери содержимого. Расчёт 32 пар токенов не равен полной сертификации WCAG.

ML: RULES/manual базовый путь; генеративный LLM/NLI/fine-tuning/RAG не обязательны. Development-метрики, исправленная регрессия смешанных субъектов RULES v2 и пределы проверки описаны в [testing](testing.md). Независимый holdout, реальные пользовательские данные и пилот не выдумываются. Возможные дополнительные модели оцениваются только после основной приёмки по лицензии, языку, скорости, памяти и measured quality.
