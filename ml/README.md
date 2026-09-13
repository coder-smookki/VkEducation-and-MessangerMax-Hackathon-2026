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

ML has no relational database and no Alembic migrations. Chroma is a derived, rebuildable read index. Java publishes a confirmed case snapshot to Kafka after a case create/status update; the consumer upserts its items into Chroma. Similarity requests contain only the confirmed query and a limit: ML selects candidates itself, scoped to the query house.

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
