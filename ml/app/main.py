from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.core.config import get_settings
from app.di import create_container, setup_container
from app.features.inference.api import router as inference_router
from app.shared.database import Database
from app.shared.migrations import upgrade_database

container = create_container()


@asynccontextmanager
async def lifespan(_app: FastAPI):
    await upgrade_database(settings.database_url)
    database = await container.get(Database)
    yield
    await database.dispose()
    await container.close()


settings = get_settings()
app = FastAPI(title=settings.app_name, version=settings.app_version, lifespan=lifespan)
setup_container(app, container)
app.include_router(inference_router)


@app.exception_handler(RequestValidationError)
async def invalid_request(_request: Request, _exception: RequestValidationError):
    # FastAPI's default detail contains input values. Do not echo residents' texts or secret-like input.
    return JSONResponse(status_code=422, content={"detail": "INVALID_COMPARE_REQUEST"})


@app.get("/healthz")
def health() -> dict[str, str]:
    return {"status": "ok", "mode": "STUB", "database": "configured", "vectorStore": "chroma"}
