# KOREN ML service

Modern FastAPI template for inference features. The service remains advisory: Java owns product state, while this service stores only ML audit records and returns suggestions.

## Structure

```text
app/
├── core/                  # settings and application-wide primitives
├── shared/                # SQLAlchemy base, DB gateway, Chroma adapter
├── features/inference/    # API, service, schemas and repository for one capability
│   └── schemas/           # one Pydantic module per contract concern
└── di.py                  # Dishka composition root
```

Each new capability should get its own feature slice with `api.py`, `service.py`, schema modules and infrastructure adapters. Keep framework and storage details inside the feature boundary.

## Configuration

Settings use the `KOREN_` prefix:

```text
KOREN_DATABASE_URL=postgresql+asyncpg://user:password@localhost/koren_ml
KOREN_CHROMA_PATH=./data/chroma
KOREN_CHROMA_COLLECTION=inference_documents
KOREN_CHROMA_ENABLED=true
```

The default SQLite URL is intended for local development. PostgreSQL is supported through `asyncpg`. The `AnalysisRun` table is managed by Alembic migrations.

Database schema is managed by Alembic. The application applies pending migrations during startup, and the Docker image runs `alembic upgrade head` before Uvicorn. For local work:

```sh
make migration-upgrade
make migration-revision message="add model metadata"
make migration-downgrade
```

Chroma is exposed through `ChromaVectorStore`, so embeddings/retrieval can be introduced without leaking the Chroma client into feature code. Its client is imported during application startup; set `KOREN_CHROMA_ENABLED=false` to skip collection initialization for contract-only tests.

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
