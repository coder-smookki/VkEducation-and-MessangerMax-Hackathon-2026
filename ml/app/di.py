from dishka import AsyncContainer, Provider, Scope, make_async_container, provide
from dishka.integrations.fastapi import setup_dishka
from fastapi import FastAPI

from app.core.config import Settings, get_settings
from app.features.inference.service import InferenceService
from app.shared.vector_store import ChromaVectorStore


class AppProvider(Provider):
    scope = Scope.APP

    @provide
    def settings(self) -> Settings:
        return get_settings()

    @provide
    def vector_store(self, settings: Settings) -> ChromaVectorStore:
        return ChromaVectorStore(settings.chroma_path, settings.chroma_collection, settings.chroma_enabled)

    @provide
    def inference_service(self, vector_store: ChromaVectorStore) -> InferenceService:
        return InferenceService(vector_store)


def create_container() -> AsyncContainer:
    return make_async_container(AppProvider())


def setup_container(app: FastAPI, container: AsyncContainer) -> None:
    setup_dishka(container, app)
