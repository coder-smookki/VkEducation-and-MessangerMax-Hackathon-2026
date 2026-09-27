# Анализ текстов КОРНЯ / ДомПульса

Состояние на 27.09.2026, опубликованный выпуск `0635314`. Python/FastAPI возвращает подсказку по тексту; Java владеет пользователями, правами, PostgreSQL и всеми продуктовыми командами. Ответ анализатора не подтверждает ремонт и не связывает отчёт автоматически. Диспетчер отдельно выбирает связи, автор отдельно сообщает наблюдение. [Контракты](../contracts/README.md), [план Семёна](../docs/team/semyon.md).

## Что работает и что остаётся расширением

| Возможность | Состояние в коде | Публикация |
|---|---|---|
| `POST /internal/v1/compare` | `stub` либо `rules`; RULES `rules-baseline-v2` возвращает метки и точные цитаты | В release выбран RULES. Java анализирует сохранённый отчёт через свою durable очередь; Mini App получает состояние и результат только от Java |
| `POST /internal/v1/extract-items` | Честный STUB: `NOT_ANALYZED`, `MODEL_NOT_CONFIGURED`, пустые proposals | Автоматическое разбиение в Mini App не подключено; работает ручной редактор |
| `POST /internal/v1/clarify-items` | Проверяет наличие category/location/symptom, вопросы адресуются только AUTHOR | Отдельный экран вопросов модели в Mini App не подключён |
| `POST /internal/v1/similar-cases` | Производный Chroma-индекс, разрешённые кандидаты, версии, отдельные пары пунктов | Should выключен: Kafka и UI поиска в release не включены; публикация продуктовых снимков в Kafka ещё не подключена к CRUD/outbox |
| `GET /healthz` | Фактические `mode`/`modelVersion` через тот же `active_mode`, что и compare; состояние включения vectorStore | Доступен внутри сети приложения, без публичного порта Python |

RULES не использует генеративный LLM, fine-tuning, RAG или закрытый датасет. Chroma используется только расширением поиска, а не разбором отчёта. При недоступности анализа Java/интерфейс оставляют ручную проверку; отсутствие подсказки не выдаётся за успешный анализ. Независимая оценка на holdout ещё не выполнена.

## Структура и ответственность

```text
app/
├── core/                  # settings
├── shared/                # производный Chroma-индекс
├── features/
│   ├── inference/         # compare, RULES, схемы ответа
│   ├── intake/            # extract, clarify, similar и их контракты
│   └── indexing/          # Kafka consumer подтверждённых снимков
└── di.py                  # Dishka composition root
```

Изменять существующий ответственный модуль; новые сервисы/слои не нужны ради структуры. У Python нет реляционной продуктовой БД, миграций Alembic, прав на статусы и выполнения команд из текста.

`CaseEventPublisher` в Java пока является отдельно подготовленным адаптером, а не доказательством отправки каждого CRUD-события. Consumer умеет повторно читать историю и защищать индекс от отката версии. При поиске Python сравнивает только переданные Java кандидаты, не расширяет доступ и не объединяет обращения. Java проверяет доступ и снимки до/после HTTP. Неполный, отключённый или устаревший индекс означает отказ анализа, а не отсутствие дублей. Условия включения, replay, пороги и failure semantics - [контракт04](../contracts/04-similar-cases.md).

## Конфигурация

Переменные `Settings` имеют префикс `KOREN_`; ниже значения по умолчанию для самостоятельного запуска, не утверждение о live-env:

```dotenv
KOREN_COMPARE_ANALYZER=stub
KOREN_CHROMA_PATH=./data/chroma
KOREN_CHROMA_COLLECTION=inference_documents
KOREN_CHROMA_ENABLED=true
KOREN_KAFKA_ENABLED=false
KOREN_KAFKA_BOOTSTRAP_SERVERS=localhost:9092
KOREN_KAFKA_TOPIC=koren.case-updates
KOREN_KAFKA_GROUP_ID=koren-ml-indexer
```

Для RULES явно задать `KOREN_COMPARE_ANALYZER=rules`. [compose.release.yaml](../compose.release.yaml) делает это для сервиса `ml` в профиле `analysis` и фиксирует Kafka `false`; backend должен иметь `ANALYSIS_ENABLED=true`. [compose.yaml](../compose.yaml) - отдельная локальная конфигурация с demo и default STUB, её не объединяют с release. Изолированный health `ok` не доказывает доставку Kafka, полноту индекса или качество подсказки.

## Запуск и проверки

Из `ml/`, Python 3.12 и зависимости из `uv.lock`:

```sh
make sync
KOREN_COMPARE_ANALYZER=rules make run
make check
make evaluate
```

`make check` выполняет format-check, Ruff, Pylint и pytest. `make format` изменяет исходники; `make openapi` обновляет машинный контракт и требует согласования потребителей. Команды проверены по [Makefile](Makefile); `make help` перечисляет отдельные цели. `make evaluate` пишет результаты в `results/`; это development/regression, не оценка новых обращений.

27.09 повторно вычислены метрики текущего `rules-baseline-v2`: development 29 пунктов, 16 содержательных ответов, точность этих ответов 16/16; regression 12 пунктов, 8 содержательных ответов, точность 8/8. Остальные ответы - отказ от содержательной интерпретации. `holdout: null`; такую оценку нельзя объявлять 100% качеством продукта. Смешанные субъекты защищены регрессией, но не гарантией на произвольный русский текст. Разметка, спорные примеры и правила независимой проверки - [data/README](data/README.md).

Доказательства backend/frontend и опубликованного выпуска: [QA27.09](../docs/qa/profile-visual-2026-09-27.md). Полный пользовательский G2 и независимый holdout остаются отдельной приёмкой Софии.
