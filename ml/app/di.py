from dishka import AsyncContainer, Provider, Scope, make_async_container, provide
from dishka.integrations.fastapi import setup_dishka
from fastapi import FastAPI

from app.core.config import Settings, get_settings
from app.features.inference.repository import AnalysisRunRepository
from app.features.inference.service import InferenceService
from app.shared.database import Database
from app.shared.vector_store import ChromaVectorStore


class AppProvider(Provider):
    scope = Scope.APP

    @provide
    def settings(self) -> Settings:
        return get_settings()

    @provide
    def database(self, settings: Settings) -> Database:
        return Database(settings.database_url)

    @provide
    def vector_store(self, settings: Settings) -> ChromaVectorStore:
        return ChromaVectorStore(settings.chroma_path, settings.chroma_collection, settings.chroma_enabled)

    @provide
    def repository(self) -> AnalysisRunRepository:
        return AnalysisRunRepository()

    @provide
    def inference_service(self, repository: AnalysisRunRepository) -> InferenceService:
        return InferenceService(repository)


def create_container() -> AsyncContainer:
    return make_async_container(AppProvider())


def setup_container(app: FastAPI, container: AsyncContainer) -> None:
    setup_dishka(container, app)
