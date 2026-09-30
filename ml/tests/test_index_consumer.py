import asyncio
import json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch
from uuid import uuid4

from aiokafka import TopicPartition

from app.core.config import Settings
from app.features.indexing.consumer import CaseEventsConsumer, ReplayIndex


def test_reassignment_replays_history_even_with_a_previously_committed_group():
    consumer = SimpleNamespace(seek_to_beginning=AsyncMock())
    partition = TopicPartition("test", 0)
    asyncio.run(ReplayIndex(consumer).on_partitions_assigned({partition}))
    consumer.seek_to_beginning.assert_awaited_once_with(partition)


def run_delivery(fail=False, wrong_key=False):
    case = json.loads(
        (Path(__file__).resolve().parents[2] / "contracts/examples/similar-request.json").read_text()
    )["candidates"][0]
    event = {"eventId": str(uuid4()), "eventType": "CASE_UPDATED", "schemaVersion": 1, "case": case}
    message = SimpleNamespace(
        topic="test",
        partition=2,
        offset=7,
        key=b"wrong" if wrong_key else case["caseId"].encode(),
        value=json.dumps(event).encode(),
    )

    class Consumer:
        def __init__(self):
            self.start = AsyncMock()
            self.stop = AsyncMock()
            self.commit = AsyncMock()
            self.subscribe = MagicMock()
            self.sent = False

        def __aiter__(self):
            return self

        async def __anext__(self):
            if self.sent:
                raise asyncio.CancelledError
            self.sent = True
            return message

    consumer = Consumer()
    order = []

    def upsert(value):
        order.append("upsert")
        if fail:
            raise OSError("index unavailable")

    async def commit(offsets):
        order.append("commit")

    consumer.commit.side_effect = commit
    store = SimpleNamespace(enabled=True, upsert_case=MagicMock(side_effect=upsert))

    async def exercise():
        with patch("app.features.indexing.consumer.AIOKafkaConsumer", return_value=consumer) as factory:
            with patch("app.features.indexing.consumer.asyncio.sleep", side_effect=asyncio.CancelledError):
                try:
                    await CaseEventsConsumer(store, Settings())._consume()
                except asyncio.CancelledError:
                    pass
            assert factory.call_args.kwargs["auto_offset_reset"] == "earliest"
            assert factory.call_args.kwargs["enable_auto_commit"] is False

    asyncio.run(exercise())
    consumer.stop.assert_awaited_once()
    return consumer, store, order


def test_offset_is_committed_for_only_the_processed_record_after_persistence():
    consumer, _, order = run_delivery()
    assert order == ["upsert", "commit"]
    consumer.commit.assert_awaited_once_with({TopicPartition("test", 2): 8})


def test_failed_write_and_wrong_key_are_never_acknowledged():
    for fail, wrong_key in [(True, False), (False, True)]:
        consumer, store, _ = run_delivery(fail=fail, wrong_key=wrong_key)
        consumer.commit.assert_not_awaited()
        if wrong_key:
            store.upsert_case.assert_not_called()


def test_disabled_index_never_starts_a_consumer():
    service = CaseEventsConsumer(SimpleNamespace(enabled=False), Settings(kafka_enabled=True))
    asyncio.run(service.start())
    assert service._task is None
