from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.core.config import get_settings
from app.di import create_container, setup_container
from app.features.indexing.consumer import CaseEventsConsumer
from app.features.inference.api import router as inference_router
from app.features.intake.api import router as intake_router
from app.http_errors import RequestSizeLimit
from app.shared.vector_store import ChromaVectorStore

container = create_container()


@asynccontextmanager
async def lifespan(_app: FastAPI):
    vector_store = await container.get(ChromaVectorStore)
    consumer = CaseEventsConsumer(vector_store, settings)
    await consumer.start()
    try:
        yield
    finally:
        await consumer.stop()
        await container.close()


settings = get_settings()
app = FastAPI(title=settings.app_name, version=settings.app_version, lifespan=lifespan)
setup_container(app, container)
app.include_router(inference_router)
app.include_router(intake_router)
app.add_middleware(RequestSizeLimit)


@app.exception_handler(RequestValidationError)
async def invalid_request(request: Request, _exception: RequestValidationError):
    # FastAPI's default detail contains input values. Do not echo residents' texts or secret-like input.
    detail = (
        "INVALID_COMPARE_REQUEST" if request.url.path == "/internal/v1/compare" else "INVALID_INTAKE_REQUEST"
    )
    return JSONResponse(status_code=422, content={"detail": detail})


@app.get("/healthz")
def health() -> dict[str, str]:
    return {
        "status": "ok",
        "mode": "STUB",
        "vectorStore": "chroma" if settings.chroma_enabled else "disabled",
    }
