"""Opt-in test against a real broker. Only the vectors are deterministic synthetic fixtures."""

import asyncio
import copy
import json
import os
from pathlib import Path
from uuid import uuid4

import pytest
from aiokafka import AIOKafkaConsumer, AIOKafkaProducer, TopicPartition
from aiokafka.admin import AIOKafkaAdminClient

from app.core.config import Settings
from app.features.indexing.consumer import CaseEventsConsumer
from app.features.intake.contracts import SimilarCasesRequest
from app.shared.vector_store import ChromaVectorStore, IndexUnavailable


@pytest.mark.skipif(not os.getenv("KAFKA_TEST_BOOTSTRAP"), reason="Real Kafka is not configured")
def test_backlog_and_rebuild_with_committed_group_offsets(tmp_path):
    async def exercise():
        broker = os.environ["KAFKA_TEST_BOOTSTRAP"]
        topic, group = "koren-test-" + uuid4().hex, "koren-test-" + uuid4().hex
        payload = json.loads(
            (Path(__file__).resolve().parents[2] / "contracts/examples/similar-request.json").read_text()
        )
        case = payload["candidates"][0]
        case["caseVersion"] = 7
        request = SimilarCasesRequest.model_validate(payload)
        producer = AIOKafkaProducer(bootstrap_servers=broker)
        admin = AIOKafkaAdminClient(bootstrap_servers=broker)
        await admin.start()
        await producer.start()
        try:
            # Publish before a consumer exists; replay must retain v7 despite a later delivery of v6.
            for version in (7, 6):
                snapshot = copy.deepcopy(case)
                snapshot["caseVersion"] = version
                event = {
                    "eventId": str(uuid4()),
                    "eventType": "CASE_UPDATED",
                    "schemaVersion": 1,
                    "case": snapshot,
                }
                sent = await producer.send_and_wait(
                    topic, json.dumps(event).encode(), key=case["caseId"].encode()
                )
            settings = Settings(
                kafka_enabled=True, kafka_bootstrap_servers=broker, kafka_topic=topic, kafka_group_id=group
            )
            # Both passes use exactly the same group. The second gets a brand new, empty local index.
            for name in ("initial", "rebuilt"):
                store = ChromaVectorStore(
                    str(tmp_path / name),
                    "test_cases",
                    embedding_function=lambda texts: [[1.0, 0.0] for _ in texts],
                )
                worker = CaseEventsConsumer(store, settings)
                observer = AIOKafkaConsumer(bootstrap_servers=broker, group_id=group)
                await observer.start()
                await worker.start()
                try:

                    async def ready(current_store=store, current_observer=observer):
                        while True:
                            committed = await current_observer.committed(
                                TopicPartition(topic, sent.partition)
                            )
                            try:
                                results = await asyncio.to_thread(current_store.search, request)
                            except IndexUnavailable:
                                results = []
                            if committed is not None and committed > sent.offset and results:
                                assert results[0]["caseVersion"] == 7
                                return
                            await asyncio.sleep(0.1)

                    await asyncio.wait_for(ready(), timeout=30)
                finally:
                    await worker.stop()
                    await observer.stop()
        finally:
            await producer.stop()
            await admin.delete_topics([topic])
            await admin.close()

    asyncio.run(exercise())
