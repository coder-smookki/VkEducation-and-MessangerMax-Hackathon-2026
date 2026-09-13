"""Real Chroma persistence; deterministic vectors isolate contract/index bugs from model quality."""

import copy
import json
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from uuid import uuid4

import pytest

from app.features.intake.contracts import SimilarCasesRequest
from app.features.intake.service import similar_cases
from app.shared.vector_store import ChromaVectorStore


def vectors(texts):
    return [
        [0.0, 0.0, 1.0]
        if "orthogonal" in text
        else [0.0, 1.0, 0.0]
        if "ENTRANCE_DOOR" in text
        else [1.0, 0.0, 0.0]
        for text in texts
    ]


@pytest.fixture
def payload():
    return json.loads(
        (Path(__file__).resolve().parents[2] / "contracts/examples/similar-request.json").read_text()
    )


@pytest.fixture
def store(tmp_path):
    return ChromaVectorStore(str(tmp_path), "test_cases", embedding_function=vectors)


def event(case):
    return {"eventId": str(uuid4()), "eventType": "CASE_UPDATED", "schemaVersion": 1, "case": case}


def analyze(store, payload):
    return similar_cases(SimilarCasesRequest.model_validate(payload), store).model_dump(mode="json")


def test_door_matches_second_item_and_is_stable_under_query_permutation(store, payload):
    store.upsert_case(event(payload["candidates"][0]))
    result = analyze(store, payload)
    assert result["status"] == "ANALYZED"
    assert result["results"][0]["relation"] == "RELATED"
    assert result["results"][0]["matchedItems"] == [
        {
            "queryItemId": payload["query"]["items"][1]["itemId"],
            "candidateItemId": payload["candidates"][0]["items"][0]["itemId"],
        }
    ]
    payload["query"]["items"].reverse()
    assert analyze(store, payload)["results"] == result["results"]


def test_weak_similarity_different_category_and_different_location_are_not_related(store, payload):
    candidate = payload["candidates"][0]
    for field, value in [("symptom", "orthogonal"), ("category", "LIGHTING"), ("location", "Другой вход")]:
        changed = copy.deepcopy(candidate)
        changed["caseVersion"] += 1
        changed["items"][0][field] = value
        store.upsert_case(event(changed))
        payload["candidates"][0] = changed
        result = analyze(store, payload)
        assert result["status"] == "ANALYZED" and result["results"] == []
        candidate["caseVersion"] += 1


def test_all_matching_items_may_propose_duplicate(store, payload):
    payload["query"]["items"] = [payload["query"]["items"][1]]
    store.upsert_case(event(payload["candidates"][0]))
    assert analyze(store, payload)["results"][0]["relation"] == "POSSIBLE_DUPLICATE"


def test_index_never_searches_cases_outside_java_allowlist(store, payload):
    neighbor = copy.deepcopy(payload["candidates"][0])
    neighbor["caseId"] = str(uuid4())
    neighbor["items"][0]["itemId"] = str(uuid4())
    store.upsert_case(event(neighbor))
    store.upsert_case(event(payload["candidates"][0]))
    assert {r["caseId"] for r in analyze(store, payload)["results"]} == {payload["candidates"][0]["caseId"]}


def test_stale_and_missing_snapshots_do_not_claim_search_succeeded(store, payload):
    assert analyze(store, payload)["status"] == "NOT_ANALYZED"
    store.upsert_case(event(payload["candidates"][0]))
    payload["candidates"][0]["caseVersion"] += 1
    assert analyze(store, payload)["status"] == "NOT_ANALYZED"


def test_disabled_index_differs_from_a_ready_empty_authorized_set(tmp_path, store, payload):
    payload["candidates"] = []
    disabled = ChromaVectorStore(str(tmp_path / "disabled"), "test_cases", enabled=False)
    assert analyze(disabled, payload)["status"] == "NOT_ANALYZED"
    assert analyze(store, payload)["status"] == "ANALYZED"


def test_old_and_duplicate_events_preserve_latest_snapshot_after_restart(tmp_path, payload):
    first = ChromaVectorStore(str(tmp_path), "versioned", embedding_function=vectors)
    newest = copy.deepcopy(payload["candidates"][0])
    newest["caseVersion"] = 7
    first.upsert_case(event(newest))
    first.upsert_case(event(payload["candidates"][0]))
    first.upsert_case(event(newest))
    reopened = ChromaVectorStore(str(tmp_path), "versioned", embedding_function=vectors)
    payload["candidates"] = [newest]
    assert analyze(reopened, payload)["results"][0]["caseVersion"] == 7


def test_same_version_conflict_and_embedding_failure_leave_last_snapshot_usable(store, payload):
    case = payload["candidates"][0]
    store.upsert_case(event(case))
    changed = copy.deepcopy(case)
    changed["sourceText"] = "Conflicting content"
    with pytest.raises(ValueError):
        store.upsert_case(event(changed))
    changed["caseVersion"] += 1
    original = store._embed
    store._embed = lambda texts: (_ for _ in ()).throw(RuntimeError("model unavailable"))
    with pytest.raises(RuntimeError):
        store.upsert_case(event(changed))
    store._embed = original
    assert analyze(store, payload)["status"] == "ANALYZED"


def test_concurrent_writers_cannot_regress_version(tmp_path, payload):
    stores = [ChromaVectorStore(str(tmp_path), "parallel", embedding_function=vectors) for _ in range(2)]

    def write(version):
        case = copy.deepcopy(payload["candidates"][0])
        case["caseVersion"] = version
        stores[version % 2].upsert_case(event(case))

    with ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(write, [7, 3, 6, 2, 9, 4, 8, 5]))
    payload["candidates"][0]["caseVersion"] = 9
    assert analyze(stores[0], payload)["results"][0]["caseVersion"] == 9
