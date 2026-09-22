# KOREN ML service

Modern FastAPI template for inference features. The service remains advisory: Java owns product state, while ML keeps a derived Chroma index and returns suggestions.

## Structure

```text
app/
├── core/                  # settings and application-wide primitives
├── shared/                # Chroma adapter and shared contracts
├── features/
│   ├── inference/         # API, service, schemas and repository
│   │   └── schemas/       # one Pydantic module per contract concern
│   ├── intake/            # extraction, clarification and Chroma similarity search
│   └── indexing/          # Kafka consumer for authorized case snapshots
└── di.py                  # Dishka composition root
```

Each new capability should get its own feature slice with `api.py`, `service.py`, schema modules and infrastructure adapters. Keep framework and storage details inside the feature boundary.

## Configuration

Settings use the `KOREN_` prefix:

```text
KOREN_CHROMA_PATH=./data/chroma
KOREN_CHROMA_COLLECTION=inference_documents
KOREN_CHROMA_ENABLED=true
KOREN_KAFKA_ENABLED=false
KOREN_KAFKA_BOOTSTRAP_SERVERS=localhost:9092
KOREN_KAFKA_TOPIC=koren.case-updates
KOREN_KAFKA_GROUP_ID=koren-ml-indexer
```

ML has no relational database and no Alembic migrations. Chroma is a derived, rebuildable read index. Java publishes a confirmed case snapshot to Kafka after a case create/status update; the consumer atomically upserts one case snapshot with its separate item vectors. Wiring publication into transactional CRUD/outbox is still pending. Similarity requests carry query, authorized candidates, and limit. Java checks access before HTTP and rechecks snapshots afterwards; ML never expands this scope. See contracts/04-similar-cases.md for replay, version guards, thresholds, and failure semantics.

## Run

```sh
make sync
make run
make test
make lint
make pylint
make check
```

Use `make help` to list all available commands. `make format` changes files; `make format-check` only verifies formatting.


## Текущий план КОРНЯ - 22.09.2026

Семён владеет Java/Python, София проверяет независимые примеры; [план](../docs/team/semyon.md). STUB - режим по умолчанию, RULES требует явного включения. Смешанные субъекты одной фразы имеют известные ошибки, health требует исправления фактического режима: S-06/Q-06. Development-набор не является holdout.

Python возвращает подсказку и точные цитаты, не подтверждает ремонт, не выполняет пользовательские команды и не владеет PostgreSQL/правами. Ручной путь обязателен. Похожие обращения/Chroma - расширение после основной приёмки. Контракты и их статусы: [каталог](../contracts/README.md).
