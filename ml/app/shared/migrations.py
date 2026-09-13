import asyncio
from pathlib import Path

from alembic.config import Config

from alembic import command


def _upgrade(database_url: str) -> None:
    config = Config(str(Path(__file__).resolve().parents[2] / "alembic.ini"))
    config.set_main_option("sqlalchemy.url", database_url.replace("%", "%%"))
    command.upgrade(config, "head")


async def upgrade_database(database_url: str) -> None:
    """Run Alembic in a worker thread because Alembic owns its async loop."""
    await asyncio.to_thread(_upgrade, database_url)
