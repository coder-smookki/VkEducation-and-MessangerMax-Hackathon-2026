import asyncio
import contextlib
import json
import logging

from aiokafka import AIOKafkaConsumer, ConsumerRebalanceListener, TopicPartition

from app.core.config import Settings
from app.features.indexing.contracts import CaseUpdatedEvent
from app.shared.vector_store import ChromaVectorStore

logger = logging.getLogger(__name__)


class ReplayIndex(ConsumerRebalanceListener):
    """A local derived index must be rebuildable even when Kafka already has committed offsets."""

    def __init__(self, consumer):
        self.consumer = consumer

    async def on_partitions_revoked(self, revoked):  # pylint: disable=invalid-overridden-method
        pass

    async def on_partitions_assigned(self, assigned):  # pylint: disable=invalid-overridden-method
        if assigned:
            await self.consumer.seek_to_beginning(*assigned)


class CaseEventsConsumer:
    def __init__(self, vector_store: ChromaVectorStore, settings: Settings) -> None:
        self.vector_store = vector_store
        self.settings = settings
        self._task: asyncio.Task[None] | None = None

    async def start(self) -> None:
        if self.settings.kafka_enabled and self.vector_store.enabled:
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
                bootstrap_servers=self.settings.kafka_bootstrap_servers,
                group_id=self.settings.kafka_group_id,
                enable_auto_commit=False,
                auto_offset_reset="earliest",
                value_deserializer=lambda value: value,
            )
            consumer.subscribe([self.settings.kafka_topic], listener=ReplayIndex(consumer))
            try:
                await consumer.start()
                async for message in consumer:
                    event = CaseUpdatedEvent.model_validate(json.loads(message.value))
                    if message.key != str(event.case.caseId).encode("utf-8"):
                        raise ValueError("Kafka key does not match snapshot")
                    await asyncio.to_thread(self.vector_store.upsert_case, event.model_dump(mode="json"))
                    await consumer.commit(
                        {TopicPartition(message.topic, message.partition): message.offset + 1}
                    )
            # Keep dependency failures retryable and unacknowledged; log only their type.
            except Exception as error:  # noqa: BLE001  # pylint: disable=broad-exception-caught
                logger.warning("Case event consumer unavailable: %s", type(error).__name__)
                await asyncio.sleep(5)
            finally:
                await consumer.stop()
