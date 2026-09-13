.DEFAULT_GOAL := help

UV ?= uv
APP_MODULE ?= app.main:app

.PHONY: help sync format format-check lint pylint test openapi evaluate migration migration-upgrade migration-downgrade migration-revision check run

help: ## Show available commands
	@awk 'BEGIN {FS = ":.*## "} /^[a-zA-Z0-9_-]+:.*## / {printf "%-16s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

sync: ## Install locked runtime and development dependencies
	$(UV) sync --frozen

format: ## Format Python sources with Ruff
	$(UV) run ruff format app scripts tests

format-check: ## Check Python formatting without changing files
	$(UV) run ruff format --check app scripts tests

lint: ## Run Ruff lint checks
	$(UV) run ruff check app scripts tests

pylint: ## Run Pylint checks
	$(UV) run pylint app scripts

test: ## Run the test suite
	$(UV) run pytest -q

openapi: ## Export the committed inference OpenAPI contract
	$(UV) run python -m scripts.export_openapi

evaluate: ## Evaluate the development examples
	$(UV) run python -m scripts.evaluate

migration: migration-upgrade ## Apply all pending database migrations

migration-upgrade: ## Upgrade the database to the latest migration
	$(UV) run alembic upgrade head

migration-downgrade: ## Roll back the latest database migration
	$(UV) run alembic downgrade -1

migration-revision: ## Create a migration (use message="describe change")
	@test -n "$(message)" || (echo 'Usage: make migration-revision message="describe change"' && exit 1)
	$(UV) run alembic revision --autogenerate -m "$(message)"

check: format-check lint pylint test ## Run all non-mutating quality checks

run: ## Start the local FastAPI development server
	$(UV) run uvicorn $(APP_MODULE) --reload
