# КОРЕНЬ — реестр изображений

Редакция 22 сентября 2026 года: приложенный UI-пакет согласован с Blueprint и палитрой MAX. 118 самостоятельных artboards; все строки ниже соответствуют PNG. Статус DONE означает готовность проектного изображения, а не готовность приложения или интеграции.

Основной каталог: 72 M/A/D + 20 X + 8 R + 8 B + 6 C = 114 PNG. Отдельное расширение S: 4 PNG. У C обе темы на одном листе.

| ID | Название | Роль | Тема | Логический размер | Масштаб | PNG, px | Файл | Предыдущее действие | Следующий шаг | Готовность | Объём |
|---|---|---|---|---|---|---|---|---|---|---|---|
| M01 | Запуск и восстановление входа | Общая | dark | 390×844 | 2× | 780×1688 | [M01_launch_dark.png](images/mobile/M01_launch_dark.png) | Открытие Mini App из MAX | M02 / M04 / A02 / D01 | DONE | MUST |
| M01 | Запуск и восстановление входа | Общая | light | 390×844 | 2× | 780×1688 | [M01_launch_light.png](images/mobile/M01_launch_light.png) | Открытие Mini App из MAX | M02 / M04 / A02 / D01 | DONE | MUST |
| M02 | Нет доступа к дому | Общая | dark | 390×844 | 2× | 780×1688 | [M02_no-house-access_dark.png](images/mobile/M02_no-house-access_dark.png) | M01: права определены, домов нет | M03 / M06 | DONE | MUST |
| M02 | Нет доступа к дому | Общая | light | 390×844 | 2× | 780×1688 | [M02_no-house-access_light.png](images/mobile/M02_no-house-access_light.png) | M01: права определены, домов нет | M03 / M06 | DONE | MUST |
| M03 | Ввод приглашения | Общая | dark | 390×844 | 2× | 780×1688 | [M03_invitation_dark.png](images/mobile/M03_invitation_dark.png) | M02: Ввести приглашение | M04 или X10 | DONE | MUST |
| M03 | Ввод приглашения | Общая | light | 390×844 | 2× | 780×1688 | [M03_invitation_light.png](images/mobile/M03_invitation_light.png) | M02: Ввести приглашение | M04 или X10 | DONE | MUST |
| M04 | Выбор доступного дома и контекста | Общая | dark | 390×844 | 2× | 780×1688 | [M04_available-contexts_dark.png](images/mobile/M04_available-contexts_dark.png) | M01 после проверки прав / переключение контекста | A02 / A01 / D01 | DONE | MUST |
| M04 | Выбор доступного дома и контекста | Общая | light | 390×844 | 2× | 780×1688 | [M04_available-contexts_light.png](images/mobile/M04_available-contexts_light.png) | M01 после проверки прав / переключение контекста | A02 / A01 / D01 | DONE | MUST |
| M05 | Настройки | Общая | dark | 390×972 | 2× | 780×1944 | [M05_settings_dark.png](images/mobile/M05_settings_dark.png) | Кнопка Настройки в шапке | Выбор применяется на месте / назад | DONE | MUST |
| M05 | Настройки | Общая | light | 390×972 | 2× | 780×1944 | [M05_settings_light.png](images/mobile/M05_settings_light.png) | Кнопка Настройки в шапке | Выбор применяется на месте / назад | DONE | MUST |
| M06 | Помощь и о сервисе | Общая | dark | 390×1249 | 2× | 780×2498 | [M06_help_dark.png](images/mobile/M06_help_dark.png) | M05 / M02 | Назад / B01 | DONE | MUST |
| M06 | Помощь и о сервисе | Общая | light | 390×1249 | 2× | 780×2498 | [M06_help_light.png](images/mobile/M06_help_light.png) | M05 / M02 | Назад / B01 | DONE | MUST |
| M07 | Сессия закончилась | Общая | dark | 390×844 | 2× | 780×1688 | [M07_session-expired_dark.png](images/mobile/M07_session-expired_dark.png) | Защищённое действие при истёкшей сессии | Повторный вход M01 | DONE | MUST |
| M07 | Сессия закончилась | Общая | light | 390×844 | 2× | 780×1688 | [M07_session-expired_light.png](images/mobile/M07_session-expired_light.png) | Защищённое действие при истёкшей сессии | Повторный вход M01 | DONE | MUST |
| M08 | Карточка недоступна | Общая | dark | 390×844 | 2× | 780×1688 | [M08_unavailable-card_dark.png](images/mobile/M08_unavailable-card_dark.png) | Недоступный / отсутствующий объект | A02 | DONE | MUST |
| M08 | Карточка недоступна | Общая | light | 390×844 | 2× | 780×1688 | [M08_unavailable-card_light.png](images/mobile/M08_unavailable-card_light.png) | Недоступный / отсутствующий объект | A02 | DONE | MUST |
| A01 | Мои обращения — пусто | Автор | dark | 390×844 | 2× | 780×1688 | [A01_my-appeals-empty_dark.png](images/mobile/A01_my-appeals-empty_dark.png) | M01: у автора ещё нет обращений | A03 | DONE | MUST |
| A01 | Мои обращения — пусто | Автор | light | 390×844 | 2× | 780×1688 | [A01_my-appeals-empty_light.png](images/mobile/A01_my-appeals-empty_light.png) | M01: у автора ещё нет обращений | A03 | DONE | MUST |
| A02 | Мои обращения — список | Автор | dark | 390×1094 | 2× | 780×2188 | [A02_my-appeals_dark.png](images/mobile/A02_my-appeals_dark.png) | M01 / возврат к списку | A11 / A03 / черновик | DONE | MUST |
| A02 | Мои обращения — список | Автор | light | 390×1094 | 2× | 780×2188 | [A02_my-appeals_light.png](images/mobile/A02_my-appeals_light.png) | M01 / возврат к списку | A11 / A03 / черновик | DONE | MUST |
| A03 | Новое обращение | Автор | dark | 390×897 | 2× | 780×1794 | [A03_new-appeal_dark.png](images/mobile/A03_new-appeal_dark.png) | A01 / A02: Новое обращение | A04 при недостатке данных / A05 | DONE | MUST |
| A03 | Новое обращение | Автор | light | 390×897 | 2× | 780×1794 | [A03_new-appeal_light.png](images/mobile/A03_new-appeal_light.png) | A01 / A02: Новое обращение | A04 при недостатке данных / A05 | DONE | MUST |
| A04 | Уточнение только автора | Автор | dark | 390×844 | 2× | 780×1688 | [A04_author-clarification_dark.png](images/mobile/A04_author-clarification_dark.png) | Описание: подъезд ещё неизвестен | A05 | DONE | MUST |
| A04 | Уточнение только автора | Автор | light | 390×844 | 2× | 780×1688 | [A04_author-clarification_light.png](images/mobile/A04_author-clarification_light.png) | Описание: подъезд ещё неизвестен | A05 | DONE | MUST |
| A05 | Проверка предложенных пунктов | Автор | dark | 390×846 | 2× | 780×1692 | [A05_review-proposed-items_dark.png](images/mobile/A05_review-proposed-items_dark.png) | A03 / A04 / A06 | A06 / A08 | DONE | MUST |
| A05 | Проверка предложенных пунктов | Автор | light | 390×846 | 2× | 780×1692 | [A05_review-proposed-items_light.png](images/mobile/A05_review-proposed-items_light.png) | A03 / A04 / A06 | A06 / A08 | DONE | MUST |
| A06 | Редактирование одного пункта | Автор | dark | 390×844 | 2× | 780×1688 | [A06_edit-draft-item_dark.png](images/mobile/A06_edit-draft-item_dark.png) | A05: Изменить / Добавить пункт | A05 / X02 при конфликте | DONE | MUST |
| A06 | Редактирование одного пункта | Автор | light | 390×844 | 2× | 780×1688 | [A06_edit-draft-item_light.png](images/mobile/A06_edit-draft-item_light.png) | A05: Изменить / Добавить пункт | A05 / X02 при конфликте | DONE | MUST |
| A07 | Ручной список без подсказки | Автор | dark | 390×958 | 2× | 780×1916 | [A07_manual-items_dark.png](images/mobile/A07_manual-items_dark.png) | Недоступна подсказка / ручной путь | A08 после достаточного ввода | DONE | MUST |
| A07 | Ручной список без подсказки | Автор | light | 390×958 | 2× | 780×1916 | [A07_manual-items_light.png](images/mobile/A07_manual-items_light.png) | Недоступна подсказка / ручной путь | A08 после достаточного ввода | DONE | MUST |
| A08 | Список подтверждён — обращение сохранено | Автор | dark | 390×844 | 2× | 780×1688 | [A08_appeal-saved_dark.png](images/mobile/A08_appeal-saved_dark.png) | A05: Подтвердить список | A09 | DONE | MUST |
| A08 | Список подтверждён — обращение сохранено | Автор | light | 390×844 | 2× | 780×1688 | [A08_appeal-saved_light.png](images/mobile/A08_appeal-saved_light.png) | A05: Подтвердить список | A09 | DONE | MUST |
| A09 | Карточка до приёма | Автор | dark | 390×855 | 2× | 780×1710 | [A09_awaiting-acceptance_dark.png](images/mobile/A09_awaiting-acceptance_dark.png) | A08 / A02 | A10 после приёма сотрудником | DONE | MUST |
| A09 | Карточка до приёма | Автор | light | 390×855 | 2× | 780×1710 | [A09_awaiting-acceptance_light.png](images/mobile/A09_awaiting-acceptance_light.png) | A08 / A02 | A10 после приёма сотрудником | DONE | MUST |
| A10 | Карточка после приёма | Автор | dark | 390×990 | 2× | 780×1980 | [A10_accepted-appeal_dark.png](images/mobile/A10_accepted-appeal_dark.png) | Приём в D03 | A11 после подтверждённой связи отчёта | DONE | MUST |
| A10 | Карточка после приёма | Автор | light | 390×990 | 2× | 780×1980 | [A10_accepted-appeal_light.png](images/mobile/A10_accepted-appeal_light.png) | Приём в D03 | A11 после подтверждённой связи отчёта | DONE | MUST |
| A11 | Есть отчёт по свету — ждём автора | Автор | dark | 390×1102 | 2× | 780×2204 | [A11_light-awaiting-observation_dark.png](images/mobile/A11_light-awaiting-observation_dark.png) | D07: отчёт связан только со светом | A12 / A14 | DONE | MUST |
| A11 | Есть отчёт по свету — ждём автора | Автор | light | 390×1102 | 2× | 780×2204 | [A11_light-awaiting-observation_light.png](images/mobile/A11_light-awaiting-observation_light.png) | D07: отчёт связан только со светом | A12 / A14 | DONE | MUST |
| A12 | Наблюдение по одному пункту | Автор | dark | 390×906 | 2× | 780×1812 | [A12_item-observation_dark.png](images/mobile/A12_item-observation_dark.png) | A11: Проверить результат | A13 / X09 | DONE | MUST |
| A12 | Наблюдение по одному пункту | Автор | light | 390×906 | 2× | 780×1812 | [A12_item-observation_light.png](images/mobile/A12_item-observation_light.png) | A11: Проверить результат | A13 / X09 | DONE | MUST |
| A13 | Частичный результат — 1 из 2 | Автор | dark | 390×977 | 2× | 780×1954 | [A13_partial-result_dark.png](images/mobile/A13_partial-result_dark.png) | A12: свет в порядке по автору | A14 / A15 | DONE | MUST |
| A13 | Частичный результат — 1 из 2 | Автор | light | 390×977 | 2× | 780×1954 | [A13_partial-result_light.png](images/mobile/A13_partial-result_light.png) | A12: свет в порядке по автору | A14 / A15 | DONE | MUST |
| A14 | Подробности двери | Автор | dark | 390×996 | 2× | 780×1992 | [A14_door-details_dark.png](images/mobile/A14_door-details_dark.png) | A13: Подробности двери | A15 / A17 | DONE | MUST |
| A14 | Подробности двери | Автор | light | 390×996 | 2× | 780×1992 | [A14_door-details_light.png](images/mobile/A14_door-details_light.png) | A13: Подробности двери | A15 / A17 | DONE | MUST |
| A15 | История обращения | Автор | dark | 390×1127 | 2× | 780×2254 | [A15_appeal-history_dark.png](images/mobile/A15_appeal-history_dark.png) | Карточка / пункт: История | Назад в тот же контекст | DONE | MUST |
| A15 | История обращения | Автор | light | 390×1127 | 2× | 780×2254 | [A15_appeal-history_light.png](images/mobile/A15_appeal-history_light.png) | Карточка / пункт: История | Назад в тот же контекст | DONE | MUST |
| A16 | По обоим пунктам всё в порядке | Автор | dark | 390×954 | 2× | 780×1908 | [A16_both-observed-ok_dark.png](images/mobile/A16_both-observed-ok_dark.png) | После отдельного отчёта по двери и нового наблюдения автора | A02 / A15 | DONE | MUST |
| A16 | По обоим пунктам всё в порядке | Автор | light | 390×954 | 2× | 780×1908 | [A16_both-observed-ok_light.png](images/mobile/A16_both-observed-ok_light.png) | После отдельного отчёта по двери и нового наблюдения автора | A02 / A15 | DONE | MUST |
| A17 | Отзыв своего пункта | Автор | dark | 390×844 | 2× | 780×1688 | [A17_withdraw-item_dark.png](images/mobile/A17_withdraw-item_dark.png) | A14: Отозвать свой пункт | A18 / A14 | DONE | MUST |
| A17 | Отзыв своего пункта | Автор | light | 390×844 | 2× | 780×1688 | [A17_withdraw-item_light.png](images/mobile/A17_withdraw-item_light.png) | A14: Отозвать свой пункт | A18 / A14 | DONE | MUST |
| A18 | Один пункт в порядке, другой отозван | Автор | dark | 390×1011 | 2× | 780×2022 | [A18_ok-and-withdrawn_dark.png](images/mobile/A18_ok-and-withdrawn_dark.png) | A17: Отозвать пункт | A02 / A15 | DONE | MUST |
| A18 | Один пункт в порядке, другой отозван | Автор | light | 390×1011 | 2× | 780×2022 | [A18_ok-and-withdrawn_light.png](images/mobile/A18_ok-and-withdrawn_light.png) | A17: Отозвать пункт | A02 / A15 | DONE | MUST |
| D01 | Рабочая очередь | Диспетчер | dark | 390×1025 | 2× | 780×2050 | [D01_work-queue_dark.png](images/mobile/D01_work-queue_dark.png) | M01: выдан контекст диспетчера | D02 / D03 / D10 | DONE | MUST |
| D01 | Рабочая очередь | Диспетчер | light | 390×1025 | 2× | 780×2050 | [D01_work-queue_light.png](images/mobile/D01_work-queue_light.png) | M01: выдан контекст диспетчера | D02 / D03 / D10 | DONE | MUST |
| D02 | Фильтры очереди | Диспетчер | dark | 390×844 | 2× | 780×1688 | [D02_queue-filters_dark.png](images/mobile/D02_queue-filters_dark.png) | D01: Все действия | D01 / X06 | DONE | MUST |
| D02 | Фильтры очереди | Диспетчер | light | 390×844 | 2× | 780×1688 | [D02_queue-filters_light.png](images/mobile/D02_queue-filters_light.png) | D01: Все действия | D01 / X06 | DONE | MUST |
| D03 | Обращение ждёт приёма | Диспетчер | dark | 390×996 | 2× | 780×1992 | [D03_accept-appeal_dark.png](images/mobile/D03_accept-appeal_dark.png) | D01: №124 | D04 после отдельного события приёма | DONE | MUST |
| D03 | Обращение ждёт приёма | Диспетчер | light | 390×996 | 2× | 780×1992 | [D03_accept-appeal_light.png](images/mobile/D03_accept-appeal_light.png) | D01: №124 | D04 после отдельного события приёма | DONE | MUST |
| D04 | Следующий шаг по двери | Диспетчер | dark | 390×1040 | 2× | 780×2080 | [D04_door-next-step_dark.png](images/mobile/D04_door-next-step_dark.png) | D03: приём / изменение плана двери | Возврат к карточке с сохранённым планом | DONE | MUST |
| D04 | Следующий шаг по двери | Диспетчер | light | 390×1040 | 2× | 780×2080 | [D04_door-next-step_light.png](images/mobile/D04_door-next-step_light.png) | D03: приём / изменение плана двери | Возврат к карточке с сохранённым планом | DONE | MUST |
| D05 | Сохранение отчёта | Диспетчер | dark | 390×881 | 2× | 780×1762 | [D05_save-report_dark.png](images/mobile/D05_save-report_dark.png) | Принятое обращение: Добавить отчёт | D06 | DONE | MUST |
| D05 | Сохранение отчёта | Диспетчер | light | 390×881 | 2× | 780×1762 | [D05_save-report_light.png](images/mobile/D05_save-report_light.png) | Принятое обращение: Добавить отчёт | D06 | DONE | MUST |
| D06 | Проверка подсказки и выбор связей | Диспетчер | dark | 390×1059 | 2× | 780×2118 | [D06_review-report-links_dark.png](images/mobile/D06_review-report-links_dark.png) | D05: Сохранить отчёт | Ручной выбор → D07 | DONE | MUST |
| D06 | Проверка подсказки и выбор связей | Диспетчер | light | 390×1059 | 2× | 780×2118 | [D06_review-report-links_light.png](images/mobile/D06_review-report-links_light.png) | D05: Сохранить отчёт | Ручной выбор → D07 | DONE | MUST |
| D07 | Подтверждение точных связей | Диспетчер | dark | 390×1104 | 2× | 780×2208 | [D07_confirm-exact-links_dark.png](images/mobile/D07_confirm-exact-links_dark.png) | D06: диспетчер вручную выбрал только свет | D08 / X08 / X03 | DONE | MUST |
| D07 | Подтверждение точных связей | Диспетчер | light | 390×1104 | 2× | 780×2208 | [D07_confirm-exact-links_light.png](images/mobile/D07_confirm-exact-links_light.png) | D06: диспетчер вручную выбрал только свет | D08 / X08 / X03 | DONE | MUST |
| D08 | Карточка после связи отчёта | Диспетчер | dark | 390×1156 | 2× | 780×2312 | [D08_linked-report-card_dark.png](images/mobile/D08_linked-report-card_dark.png) | D07: связь подтверждена | D04 / D05 / D09 / История | DONE | MUST |
| D08 | Карточка после связи отчёта | Диспетчер | light | 390×1156 | 2× | 780×2312 | [D08_linked-report-card_light.png](images/mobile/D08_linked-report-card_light.png) | D07: связь подтверждена | D04 / D05 / D09 / История | DONE | MUST |
| D09 | Архивирование пункта с причиной | Диспетчер | dark | 390×892 | 2× | 780×1784 | [D09_archive-with-reason_dark.png](images/mobile/D09_archive-with-reason_dark.png) | D08: Архивировать с причиной | D10 / D08 | DONE | MUST |
| D09 | Архивирование пункта с причиной | Диспетчер | light | 390×892 | 2× | 780×1784 | [D09_archive-with-reason_light.png](images/mobile/D09_archive-with-reason_light.png) | D08: Архивировать с причиной | D10 / D08 | DONE | MUST |
| D10 | Пункт в архиве с неподтверждённым результатом | Диспетчер | dark | 390×1166 | 2× | 780×2332 | [D10_archive-unconfirmed-remainder_dark.png](images/mobile/D10_archive-unconfirmed-remainder_dark.png) | D09: архивировать | История / D01 | DONE | MUST |
| D10 | Пункт в архиве с неподтверждённым результатом | Диспетчер | light | 390×1166 | 2× | 780×2332 | [D10_archive-unconfirmed-remainder_light.png](images/mobile/D10_archive-unconfirmed-remainder_light.png) | D09: архивировать | История / D01 | DONE | MUST |
| X01 | Ошибка обязательного поля | Автор | dark | 390×850 | 2× | 780×1700 | [X01_required-field-error_dark.png](images/states/X01_required-field-error_dark.png) | A03: попытка отправить пустое описание | Исправление → A05 | DONE | MUST |
| X01 | Ошибка обязательного поля | Автор | light | 390×850 | 2× | 780×1700 | [X01_required-field-error_light.png](images/states/X01_required-field-error_light.png) | A03: попытка отправить пустое описание | Исправление → A05 | DONE | MUST |
| X02 | Конфликт изменения | Диспетчер | dark | 390×844 | 2× | 780×1688 | [X02_version-conflict_dark.png](images/states/X02_version-conflict_dark.png) | D04: версия устарела при записи | Перечитать → сравнить → заново решить | DONE | MUST |
| X02 | Конфликт изменения | Диспетчер | light | 390×844 | 2× | 780×1688 | [X02_version-conflict_light.png](images/states/X02_version-conflict_light.png) | D04: версия устарела при записи | Перечитать → сравнить → заново решить | DONE | MUST |
| X03 | Потеря сети — неизвестный исход | Диспетчер | dark | 390×851 | 2× | 780×1702 | [X03_unknown-save-outcome_dark.png](images/states/X03_unknown-save-outcome_dark.png) | D05: соединение потеряно после отправки | Проверка / повтор той же команды | DONE | MUST |
| X03 | Потеря сети — неизвестный исход | Диспетчер | light | 390×851 | 2× | 780×1702 | [X03_unknown-save-outcome_light.png](images/states/X03_unknown-save-outcome_light.png) | D05: соединение потеряно после отправки | Проверка / повтор той же команды | DONE | MUST |
| X04 | Подсказка недоступна — ручные связи | Диспетчер | dark | 390×976 | 2× | 780×1952 | [X04_manual-links-fallback_dark.png](images/states/X04_manual-links-fallback_dark.png) | D06: недоступна подсказка | Ручной выбор → D07 | DONE | MUST |
| X04 | Подсказка недоступна — ручные связи | Диспетчер | light | 390×976 | 2× | 780×1952 | [X04_manual-links-fallback_light.png](images/states/X04_manual-links-fallback_light.png) | D06: недоступна подсказка | Ручной выбор → D07 | DONE | MUST |
| X05 | Карточка не загрузилась | Автор | dark | 390×844 | 2× | 780×1688 | [X05_card-load-error_dark.png](images/states/X05_card-load-error_dark.png) | Ошибка чтения карточки | Повторить запрос / A02 | DONE | MUST |
| X05 | Карточка не загрузилась | Автор | light | 390×844 | 2× | 780×1688 | [X05_card-load-error_light.png](images/states/X05_card-load-error_light.png) | Ошибка чтения карточки | Повторить запрос / A02 | DONE | MUST |
| X06 | Пустая очередь и пустой фильтр | Диспетчер | dark | 852×1003 | 2× | 1704×2006 | [X06_empty-queue-and-filter_dark.png](images/states/X06_empty-queue-and-filter_dark.png) | D01 после загрузки / D02 после фильтра | Остаться / сбросить фильтр | DONE | MUST |
| X06 | Пустая очередь и пустой фильтр | Диспетчер | light | 852×1003 | 2× | 1704×2006 | [X06_empty-queue-and-filter_light.png](images/states/X06_empty-queue-and-filter_light.png) | D01 после загрузки / D02 после фильтра | Остаться / сбросить фильтр | DONE | MUST |
| X07 | Долгий ввод и клавиатура | Автор | dark | 390×844 | 2× | 780×1688 | [X07_keyboard-and-long-input_dark.png](images/states/X07_keyboard-and-long-input_dark.png) | A03: активное поле и открытая клавиатура | Продолжить / закрыть клавиатуру | DONE | MUST |
| X07 | Долгий ввод и клавиатура | Автор | light | 390×844 | 2× | 780×1688 | [X07_keyboard-and-long-input_light.png](images/states/X07_keyboard-and-long-input_light.png) | A03: активное поле и открытая клавиатура | Продолжить / закрыть клавиатуру | DONE | MUST |
| X08 | Отправка — защита от двойного нажатия | Диспетчер | dark | 390×1095 | 2× | 780×2190 | [X08_submitting-links_dark.png](images/states/X08_submitting-links_dark.png) | D07: нажатие Связать со светом | D08 / X03 / X02 | DONE | MUST |
| X08 | Отправка — защита от двойного нажатия | Диспетчер | light | 390×1095 | 2× | 780×2190 | [X08_submitting-links_light.png](images/states/X08_submitting-links_light.png) | D07: нажатие Связать со светом | D08 / X03 / X02 | DONE | MUST |
| X09 | Проблема осталась / невозможно проверить | Автор | dark | 852×1319 | 2× | 1704×2638 | [X09_negative-and-unable-observations_dark.png](images/states/X09_negative-and-unable-observations_dark.png) | A12: Проблема осталась / Не могу проверить | Диспетчер уточняет шаг / автор возвращается к проверке | DONE | MUST |
| X09 | Проблема осталась / невозможно проверить | Автор | light | 852×1319 | 2× | 1704×2638 | [X09_negative-and-unable-observations_light.png](images/states/X09_negative-and-unable-observations_light.png) | A12: Проблема осталась / Не могу проверить | Диспетчер уточняет шаг / автор возвращается к проверке | DONE | MUST |
| X10 | Ошибки приглашения | Общая | dark | 1266×1003 | 2× | 2532×2006 | [X10_invitation-errors_dark.png](images/states/X10_invitation-errors_dark.png) | M03: код не принят | Исправить / запросить новое приглашение у организации | DONE | MUST |
| X10 | Ошибки приглашения | Общая | light | 1266×1003 | 2× | 2532×2006 | [X10_invitation-errors_light.png](images/states/X10_invitation-errors_light.png) | M03: код не принят | Исправить / запросить новое приглашение у организации | DONE | MUST |
| R01 | 320 px · текст 200% | Автор | dark | 320×2179 | 2× | 640×4358 | [R01_320px-text-200_dark.png](images/responsive/R01_320px-text-200_dark.png) | A13 с увеличением всего текста до 200% | A14 / A15 | DONE | MUST |
| R01 | 320 px · текст 200% | Автор | light | 320×2179 | 2× | 640×4358 | [R01_320px-text-200_light.png](images/responsive/R01_320px-text-200_light.png) | A13 с увеличением всего текста до 200% | A14 / A15 | DONE | MUST |
| R02 | 768 × 1024 · web-контейнер | Автор | dark | 768×1024 | 2× | 1536×2048 | [R02_tablet-max-container_dark.png](images/responsive/R02_tablet-max-container_dark.png) | A13 в доступной web-области | A14 / A15 | DONE | MUST |
| R02 | 768 × 1024 · web-контейнер | Автор | light | 768×1024 | 2× | 1536×2048 | [R02_tablet-max-container_light.png](images/responsive/R02_tablet-max-container_light.png) | A13 в доступной web-области | A14 / A15 | DONE | MUST |
| R03 | 1440 × 1000 · широкая очередь | Диспетчер | dark | 1440×1000 | 2× | 2880×2000 | [R03_wide-work-queue_dark.png](images/responsive/R03_wide-work-queue_dark.png) | D01 в широком доступном окне Mini App | D02 / D03 / D10 | DONE | MUST |
| R03 | 1440 × 1000 · широкая очередь | Диспетчер | light | 1440×1000 | 2× | 2880×2000 | [R03_wide-work-queue_light.png](images/responsive/R03_wide-work-queue_light.png) | D01 в широком доступном окне Mini App | D02 / D03 / D10 | DONE | MUST |
| R04 | 1440 × 1000 · связи отчёта | Диспетчер | dark | 1440×1000 | 2× | 2880×2000 | [R04_wide-report-links_dark.png](images/responsive/R04_wide-report-links_dark.png) | D06 в широком окне | Ручной выбор → D07 | DONE | MUST |
| R04 | 1440 × 1000 · связи отчёта | Диспетчер | light | 1440×1000 | 2× | 2880×2000 | [R04_wide-report-links_light.png](images/responsive/R04_wide-report-links_light.png) | D06 в широком окне | Ручной выбор → D07 | DONE | MUST |
| B01 | Бот — старт | Автор | dark | 438×1025 | 2× | 876×2050 | [B01_bot-start_dark.png](images/bot/B01_bot-start_dark.png) | /start | Новое обращение / Мои обращения / Mini App | DONE | MUST |
| B01 | Бот — старт | Автор | light | 438×1025 | 2× | 876×2050 | [B01_bot-start_light.png](images/bot/B01_bot-start_light.png) | /start | Новое обращение / Мои обращения / Mini App | DONE | MUST |
| B02 | Бот — текст, уточнение, подтверждение | Автор | dark | 1266×1024 | 2× | 2532×2048 | [B02_bot-create-appeal_dark.png](images/bot/B02_bot-create-appeal_dark.png) | Бот: Новое обращение | Текст → уточнение автора → список → подтверждение → №124 | DONE | MUST |
| B02 | Бот — текст, уточнение, подтверждение | Автор | light | 1266×1024 | 2× | 2532×2048 | [B02_bot-create-appeal_light.png](images/bot/B02_bot-create-appeal_light.png) | Бот: Новое обращение | Текст → уточнение автора → список → подтверждение → №124 | DONE | MUST |
| B03 | Бот — отчёт, наблюдение, остаток | Автор | dark | 1266×1120 | 2× | 2532×2240 | [B03_bot-observation_dark.png](images/bot/B03_bot-observation_dark.png) | Свет связан с новым отчётом | Выбор → подтверждение наблюдения → отдельный остаток двери | DONE | MUST |
| B03 | Бот — отчёт, наблюдение, остаток | Автор | light | 1266×1120 | 2× | 2532×2240 | [B03_bot-observation_light.png](images/bot/B03_bot-observation_light.png) | Свет связан с новым отчётом | Выбор → подтверждение наблюдения → отдельный остаток двери | DONE | MUST |
| B04 | Бот — мои обращения и устаревшая кнопка | Автор | dark | 852×1050 | 2× | 1704×2100 | [B04_bot-my-and-stale-action_dark.png](images/bot/B04_bot-my-and-stale-action_dark.png) | /my / старое сообщение бота | Открыть ту же карточку / перечитать актуальную версию | DONE | MUST |
| B04 | Бот — мои обращения и устаревшая кнопка | Автор | light | 852×1050 | 2× | 1704×2100 | [B04_bot-my-and-stale-action_light.png](images/bot/B04_bot-my-and-stale-action_light.png) | /my / старое сообщение бота | Открыть ту же карточку / перечитать актуальную версию | DONE | MUST |
| S01 | Похожие пункты в разрешённой области | Автор | dark | 390×1021 | 2× | 780×2042 | [S01_similar-permitted-items_dark.png](extension/S01_similar-permitted-items_dark.png) | Необязательная проверка своего нового пункта | S02 / A05 | DONE | EXTENSION |
| S01 | Похожие пункты в разрешённой области | Автор | light | 390×1021 | 2× | 780×2042 | [S01_similar-permitted-items_light.png](extension/S01_similar-permitted-items_light.png) | Необязательная проверка своего нового пункта | S02 / A05 | DONE | EXTENSION |
| S02 | Сравнение / поиск не выполнен | Автор | dark | 852×1003 | 2× | 1704×2006 | [S02_similarity-detail-and-unavailable_dark.png](extension/S02_similarity-detail-and-unavailable_dark.png) | S01 / ошибка необязательного поиска | Продолжить новое обращение | DONE | EXTENSION |
| S02 | Сравнение / поиск не выполнен | Автор | light | 852×1003 | 2× | 1704×2006 | [S02_similarity-detail-and-unavailable_light.png](extension/S02_similarity-detail-and-unavailable_light.png) | S01 / ошибка необязательного поиска | Продолжить новое обращение | DONE | EXTENSION |
| C01 | Основы | Библиотека | both | 1440×2175 | 1× | 1440×2175 | [C01_foundations_both.png](images/components/C01_foundations_both.png) | Использованные в M/A/D/X/R/B/S компоненты | Повторное использование исходных компонентов | DONE | COMPONENT |
| C02 | Действия и ввод | Библиотека | both | 1440×2385 | 1× | 1440×2385 | [C02_actions-and-inputs_both.png](images/components/C02_actions-and-inputs_both.png) | Использованные в M/A/D/X/R/B/S компоненты | Повторное использование исходных компонентов | DONE | COMPONENT |
| C03 | Навигация и оформление | Библиотека | both | 1440×2407 | 1× | 1440×2407 | [C03_navigation-and-preferences_both.png](images/components/C03_navigation-and-preferences_both.png) | Использованные в M/A/D/X/R/B/S компоненты | Повторное использование исходных компонентов | DONE | COMPONENT |
| C04 | Предметные компоненты | Библиотека | both | 1440×2311 | 1× | 1440×2311 | [C04_domain-components_both.png](images/components/C04_domain-components_both.png) | Использованные в M/A/D/X/R/B/S компоненты | Повторное использование исходных компонентов | DONE | COMPONENT |
| C05 | Обратная связь | Библиотека | both | 1440×2505 | 1× | 1440×2505 | [C05_feedback_both.png](images/components/C05_feedback_both.png) | Использованные в M/A/D/X/R/B/S компоненты | Повторное использование исходных компонентов | DONE | COMPONENT |
| C06 | Подтверждения и история | Библиотека | both | 1440×2317 | 1× | 1440×2317 | [C06_confirmation-and-history_both.png](images/components/C06_confirmation-and-history_both.png) | Использованные в M/A/D/X/R/B/S компоненты | Повторное использование исходных компонентов | DONE | COMPONENT |

## Первые видимые экраны

Для длинных мобильных страниц дополнительно экспортирован viewport 390×844 (для R01 — 320×844). Он показывает начальную видимую область и отдельную нижнюю панель действия. Это дополнительные изображения, не новые ID.

| ID | Тема | Логический viewport | PNG | Файл |
|---|---|---|---|---|
| M05 | dark | 390×844 | 780×1688 | [M05_settings_dark_viewport.png](images/first-folds/M05_settings_dark_viewport.png) |
| M05 | light | 390×844 | 780×1688 | [M05_settings_light_viewport.png](images/first-folds/M05_settings_light_viewport.png) |
| M06 | dark | 390×844 | 780×1688 | [M06_help_dark_viewport.png](images/first-folds/M06_help_dark_viewport.png) |
| M06 | light | 390×844 | 780×1688 | [M06_help_light_viewport.png](images/first-folds/M06_help_light_viewport.png) |
| A02 | dark | 390×844 | 780×1688 | [A02_my-appeals_dark_viewport.png](images/first-folds/A02_my-appeals_dark_viewport.png) |
| A02 | light | 390×844 | 780×1688 | [A02_my-appeals_light_viewport.png](images/first-folds/A02_my-appeals_light_viewport.png) |
| A03 | dark | 390×844 | 780×1688 | [A03_new-appeal_dark_viewport.png](images/first-folds/A03_new-appeal_dark_viewport.png) |
| A03 | light | 390×844 | 780×1688 | [A03_new-appeal_light_viewport.png](images/first-folds/A03_new-appeal_light_viewport.png) |
| A05 | dark | 390×844 | 780×1688 | [A05_review-proposed-items_dark_viewport.png](images/first-folds/A05_review-proposed-items_dark_viewport.png) |
| A05 | light | 390×844 | 780×1688 | [A05_review-proposed-items_light_viewport.png](images/first-folds/A05_review-proposed-items_light_viewport.png) |
| A07 | dark | 390×844 | 780×1688 | [A07_manual-items_dark_viewport.png](images/first-folds/A07_manual-items_dark_viewport.png) |
| A07 | light | 390×844 | 780×1688 | [A07_manual-items_light_viewport.png](images/first-folds/A07_manual-items_light_viewport.png) |
| A09 | dark | 390×844 | 780×1688 | [A09_awaiting-acceptance_dark_viewport.png](images/first-folds/A09_awaiting-acceptance_dark_viewport.png) |
| A09 | light | 390×844 | 780×1688 | [A09_awaiting-acceptance_light_viewport.png](images/first-folds/A09_awaiting-acceptance_light_viewport.png) |
| A10 | dark | 390×844 | 780×1688 | [A10_accepted-appeal_dark_viewport.png](images/first-folds/A10_accepted-appeal_dark_viewport.png) |
| A10 | light | 390×844 | 780×1688 | [A10_accepted-appeal_light_viewport.png](images/first-folds/A10_accepted-appeal_light_viewport.png) |
| A11 | dark | 390×844 | 780×1688 | [A11_light-awaiting-observation_dark_viewport.png](images/first-folds/A11_light-awaiting-observation_dark_viewport.png) |
| A11 | light | 390×844 | 780×1688 | [A11_light-awaiting-observation_light_viewport.png](images/first-folds/A11_light-awaiting-observation_light_viewport.png) |
| A12 | dark | 390×844 | 780×1688 | [A12_item-observation_dark_viewport.png](images/first-folds/A12_item-observation_dark_viewport.png) |
| A12 | light | 390×844 | 780×1688 | [A12_item-observation_light_viewport.png](images/first-folds/A12_item-observation_light_viewport.png) |
| A13 | dark | 390×844 | 780×1688 | [A13_partial-result_dark_viewport.png](images/first-folds/A13_partial-result_dark_viewport.png) |
| A13 | light | 390×844 | 780×1688 | [A13_partial-result_light_viewport.png](images/first-folds/A13_partial-result_light_viewport.png) |
| A14 | dark | 390×844 | 780×1688 | [A14_door-details_dark_viewport.png](images/first-folds/A14_door-details_dark_viewport.png) |
| A14 | light | 390×844 | 780×1688 | [A14_door-details_light_viewport.png](images/first-folds/A14_door-details_light_viewport.png) |
| A15 | dark | 390×844 | 780×1688 | [A15_appeal-history_dark_viewport.png](images/first-folds/A15_appeal-history_dark_viewport.png) |
| A15 | light | 390×844 | 780×1688 | [A15_appeal-history_light_viewport.png](images/first-folds/A15_appeal-history_light_viewport.png) |
| A16 | dark | 390×844 | 780×1688 | [A16_both-observed-ok_dark_viewport.png](images/first-folds/A16_both-observed-ok_dark_viewport.png) |
| A16 | light | 390×844 | 780×1688 | [A16_both-observed-ok_light_viewport.png](images/first-folds/A16_both-observed-ok_light_viewport.png) |
| A18 | dark | 390×844 | 780×1688 | [A18_ok-and-withdrawn_dark_viewport.png](images/first-folds/A18_ok-and-withdrawn_dark_viewport.png) |
| A18 | light | 390×844 | 780×1688 | [A18_ok-and-withdrawn_light_viewport.png](images/first-folds/A18_ok-and-withdrawn_light_viewport.png) |
| D01 | dark | 390×844 | 780×1688 | [D01_work-queue_dark_viewport.png](images/first-folds/D01_work-queue_dark_viewport.png) |
| D01 | light | 390×844 | 780×1688 | [D01_work-queue_light_viewport.png](images/first-folds/D01_work-queue_light_viewport.png) |
| D03 | dark | 390×844 | 780×1688 | [D03_accept-appeal_dark_viewport.png](images/first-folds/D03_accept-appeal_dark_viewport.png) |
| D03 | light | 390×844 | 780×1688 | [D03_accept-appeal_light_viewport.png](images/first-folds/D03_accept-appeal_light_viewport.png) |
| D04 | dark | 390×844 | 780×1688 | [D04_door-next-step_dark_viewport.png](images/first-folds/D04_door-next-step_dark_viewport.png) |
| D04 | light | 390×844 | 780×1688 | [D04_door-next-step_light_viewport.png](images/first-folds/D04_door-next-step_light_viewport.png) |
| D05 | dark | 390×844 | 780×1688 | [D05_save-report_dark_viewport.png](images/first-folds/D05_save-report_dark_viewport.png) |
| D05 | light | 390×844 | 780×1688 | [D05_save-report_light_viewport.png](images/first-folds/D05_save-report_light_viewport.png) |
| D06 | dark | 390×844 | 780×1688 | [D06_review-report-links_dark_viewport.png](images/first-folds/D06_review-report-links_dark_viewport.png) |
| D06 | light | 390×844 | 780×1688 | [D06_review-report-links_light_viewport.png](images/first-folds/D06_review-report-links_light_viewport.png) |
| D07 | dark | 390×844 | 780×1688 | [D07_confirm-exact-links_dark_viewport.png](images/first-folds/D07_confirm-exact-links_dark_viewport.png) |
| D07 | light | 390×844 | 780×1688 | [D07_confirm-exact-links_light_viewport.png](images/first-folds/D07_confirm-exact-links_light_viewport.png) |
| D08 | dark | 390×844 | 780×1688 | [D08_linked-report-card_dark_viewport.png](images/first-folds/D08_linked-report-card_dark_viewport.png) |
| D08 | light | 390×844 | 780×1688 | [D08_linked-report-card_light_viewport.png](images/first-folds/D08_linked-report-card_light_viewport.png) |
| D09 | dark | 390×844 | 780×1688 | [D09_archive-with-reason_dark_viewport.png](images/first-folds/D09_archive-with-reason_dark_viewport.png) |
| D09 | light | 390×844 | 780×1688 | [D09_archive-with-reason_light_viewport.png](images/first-folds/D09_archive-with-reason_light_viewport.png) |
| D10 | dark | 390×844 | 780×1688 | [D10_archive-unconfirmed-remainder_dark_viewport.png](images/first-folds/D10_archive-unconfirmed-remainder_dark_viewport.png) |
| D10 | light | 390×844 | 780×1688 | [D10_archive-unconfirmed-remainder_light_viewport.png](images/first-folds/D10_archive-unconfirmed-remainder_light_viewport.png) |
| X01 | dark | 390×844 | 780×1688 | [X01_required-field-error_dark_viewport.png](images/first-folds/X01_required-field-error_dark_viewport.png) |
| X01 | light | 390×844 | 780×1688 | [X01_required-field-error_light_viewport.png](images/first-folds/X01_required-field-error_light_viewport.png) |
| X03 | dark | 390×844 | 780×1688 | [X03_unknown-save-outcome_dark_viewport.png](images/first-folds/X03_unknown-save-outcome_dark_viewport.png) |
| X03 | light | 390×844 | 780×1688 | [X03_unknown-save-outcome_light_viewport.png](images/first-folds/X03_unknown-save-outcome_light_viewport.png) |
| X04 | dark | 390×844 | 780×1688 | [X04_manual-links-fallback_dark_viewport.png](images/first-folds/X04_manual-links-fallback_dark_viewport.png) |
| X04 | light | 390×844 | 780×1688 | [X04_manual-links-fallback_light_viewport.png](images/first-folds/X04_manual-links-fallback_light_viewport.png) |
| X08 | dark | 390×844 | 780×1688 | [X08_submitting-links_dark_viewport.png](images/first-folds/X08_submitting-links_dark_viewport.png) |
| X08 | light | 390×844 | 780×1688 | [X08_submitting-links_light_viewport.png](images/first-folds/X08_submitting-links_light_viewport.png) |
| R01 | dark | 320×844 | 640×1688 | [R01_320px-text-200_dark_viewport.png](images/first-folds/R01_320px-text-200_dark_viewport.png) |
| R01 | light | 320×844 | 640×1688 | [R01_320px-text-200_light_viewport.png](images/first-folds/R01_320px-text-200_light_viewport.png) |
| S01 | dark | 390×844 | 780×1688 | [S01_similar-permitted-items_dark_viewport.png](extension/first-folds/S01_similar-permitted-items_dark_viewport.png) |
| S01 | light | 390×844 | 780×1688 | [S01_similar-permitted-items_light_viewport.png](extension/first-folds/S01_similar-permitted-items_light_viewport.png) |

Всего дополнительных первых экранов: 60.

Обзорных листов: 12; список — `overview/overview-manifest.json`.

PENDING: нет в каталоге проектных изображений. Неподключённые интеграции и ограничения прототипа перечислены в README.md.