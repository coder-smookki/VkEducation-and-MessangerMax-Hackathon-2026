from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Runtime configuration; local defaults keep the template runnable."""

    model_config = SettingsConfigDict(env_prefix="KOREN_", env_file=".env", extra="ignore")

    app_name: str = "KOREN inference"
    app_version: str = "0.2.0"
    database_url: str = "sqlite+aiosqlite:///./data/ml.sqlite3"
    chroma_path: str = "./data/chroma"
    chroma_collection: str = "inference_documents"
    chroma_enabled: bool = True
    chroma_embedding_function: str = Field(default="default", description="Chroma embedding provider")


@lru_cache
def get_settings() -> Settings:
    return Settings()
