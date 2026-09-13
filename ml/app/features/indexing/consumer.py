import asyncio
import contextlib
import json
import logging

from aiokafka import AIOKafkaConsumer
from aiokafka.errors import KafkaError

from app.core.config import Settings
from app.features.indexing.contracts import CaseUpdatedEvent
from app.shared.vector_store import ChromaVectorStore

logger = logging.getLogger(__name__)


class CaseEventsConsumer:
    def __init__(self, vector_store: ChromaVectorStore, settings: Settings) -> None:
        self.vector_store = vector_store
        self.settings = settings
        self._task: asyncio.Task[None] | None = None

    async def start(self) -> None:
        if self.settings.kafka_enabled:
            self._task = asyncio.create_task(self._consume())

    async def stop(self) -> None:
        if self._task is None:
            return
        self._task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await self._task

    async def _consume(self) -> None:
        while True:
            consumer = AIOKafkaConsumer(
                self.settings.kafka_topic,
                bootstrap_servers=self.settings.kafka_bootstrap_servers,
                group_id=self.settings.kafka_group_id,
                enable_auto_commit=False,
                value_deserializer=lambda value: value,
            )
            try:
                await consumer.start()
                async for message in consumer:
                    event = CaseUpdatedEvent.model_validate(json.loads(message.value))
                    await asyncio.to_thread(self.vector_store.upsert_case, event.model_dump(mode="json"))
                    await consumer.commit()
            except (KafkaError, OSError, json.JSONDecodeError, ValueError) as error:
                logger.warning("Case event consumer unavailable: %s", type(error).__name__)
                await asyncio.sleep(5)
            finally:
                await consumer.stop()
