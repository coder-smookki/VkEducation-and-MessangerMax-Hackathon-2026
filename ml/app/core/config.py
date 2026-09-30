from functools import lru_cache
from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Runtime configuration; local defaults keep the template runnable."""

    model_config = SettingsConfigDict(env_prefix="KOREN_", env_file=".env", extra="ignore")

    app_name: str = "KOREN inference"
    app_version: str = "0.2.0"
    chroma_path: str = "./data/chroma"
    chroma_collection: str = "inference_documents"
    chroma_enabled: bool = True
    # KOREN_COMPARE_ANALYZER: "stub" (default, contract reference) or "rules" (baseline).
    compare_analyzer: Literal["stub", "rules"] = "stub"
    kafka_enabled: bool = False
    kafka_bootstrap_servers: str = "localhost:9092"
    kafka_topic: str = "koren.case-updates"
    kafka_group_id: str = "koren-ml-indexer"


@lru_cache
def get_settings() -> Settings:
    return Settings()
