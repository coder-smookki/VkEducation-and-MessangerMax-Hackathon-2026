"""A derived index: one atomic Chroma record per confirmed case, with separate item vectors."""

import fcntl
import json
import math
import threading
import unicodedata
from contextlib import contextmanager
from pathlib import Path

import chromadb
from chromadb.utils.embedding_functions import DefaultEmbeddingFunction

from app.features.indexing.contracts import CaseUpdatedEvent
from app.features.intake.contracts import ConfirmedCaseContext, SimilarCasesRequest

MODEL_VERSION = "chroma-minilm-items-v2"
# Conservative development defaults, not measured accuracy or probability of a shared incident.
RELATED_THRESHOLD = 0.85
DUPLICATE_THRESHOLD = 0.95


class IndexUnavailable(RuntimeError):
    """The requested authorized snapshot set cannot currently be analyzed."""


def canonical_case(case: ConfirmedCaseContext) -> dict:
    value = case.model_dump(mode="json")
    value["items"].sort(key=lambda item: item["itemId"])
    return value


def normalized(value: str) -> str:
    return " ".join(unicodedata.normalize("NFKC", value).casefold().replace("ё", "е").split())


def cosine(left: list[float], right: list[float]) -> float:
    if len(left) != len(right) or not left:
        raise IndexUnavailable("Incompatible embeddings")
    length = math.sqrt(math.fsum(x * x for x in left) * math.fsum(x * x for x in right))
    if not length or not all(math.isfinite(x) for x in (*left, *right)):
        raise IndexUnavailable("Invalid embeddings")
    return min(1.0, max(0.0, math.fsum(x * y for x, y in zip(left, right)) / length))


def matching_items(query, candidate, query_vectors, vectors):
    """Keep the item that produced each score; never attach an aggregate score to item zero."""
    matches = []
    for query_item, query_vector in zip(query.items, query_vectors):
        for item, vector in zip(candidate.items, vectors):
            if query_item.category != item.category or normalized(query_item.location) != normalized(
                item.location
            ):
                continue
            score = cosine(query_vector, vector)
            if score >= RELATED_THRESHOLD:
                matches.append((score, str(query_item.itemId), str(item.itemId)))
    pairs, used_query, used_candidate = [], set(), set()
    for score, query_id, item_id in sorted(matches, key=lambda match: (-match[0], match[1], match[2])):
        if query_id not in used_query and item_id not in used_candidate:
            used_query.add(query_id)
            used_candidate.add(item_id)
            pairs.append((score, {"queryItemId": query_id, "candidateItemId": item_id}))
    return pairs


class ChromaVectorStore:
    def __init__(self, path: str, collection: str, enabled: bool = True, embedding_function=None) -> None:
        self._collection = None
        self._mutex = threading.RLock()
        self._lock_path = Path(path) / "snapshot-writer.lock"
        self._embed = embedding_function if embedding_function is not None else DefaultEmbeddingFunction()
        if enabled:
            client = chromadb.PersistentClient(path=path)
            # The old per-item format is deliberately separate; Kafka replay rebuilds this collection.
            self._collection = client.get_or_create_collection(
                name=f"{collection}_snapshots_v2",
                embedding_function=None,
                configuration={"hnsw": {"space": "cosine"}},
            )

    @property
    def enabled(self) -> bool:
        return self._collection is not None

    @contextmanager
    def _locked(self):
        if not self.enabled:
            raise IndexUnavailable("Index is disabled")
        # Linux containers/macOS: also serialize writers from different processes using the same volume.
        with self._mutex, self._lock_path.open("a") as lock_file:
            fcntl.flock(lock_file, fcntl.LOCK_EX)
            try:
                yield
            finally:
                fcntl.flock(lock_file, fcntl.LOCK_UN)

    def _vectors(self, case: ConfirmedCaseContext) -> list[list[float]]:
        texts = [f"{item.category.value} {item.location} {item.symptom}" for item in case.items]
        vectors = [[float(value) for value in vector] for vector in self._embed(texts)]
        if len(vectors) != len(case.items):
            raise IndexUnavailable("Missing embeddings")
        for vector in vectors:
            cosine(vector, vector)
        return vectors

    def upsert_case(self, payload: dict) -> None:
        event = CaseUpdatedEvent.model_validate(payload)
        case = event.case
        with self._locked():
            existing = self._collection.get(ids=[str(case.caseId)], include=["documents"])
            if existing["ids"]:
                previous = ConfirmedCaseContext.model_validate(json.loads(existing["documents"][0])["case"])
                if case.caseVersion < previous.caseVersion:
                    return
                if case.caseVersion == previous.caseVersion:
                    if canonical_case(case) != canonical_case(previous):
                        raise ValueError("Conflicting snapshot at the same version")
                    return
                if case.houseId != previous.houseId or case.sourceRevision < previous.sourceRevision:
                    raise ValueError("Snapshot regresses or changes house")
            vectors = self._vectors(case)
            # Compute everything before writing. A failed embedding leaves the previous snapshot intact.
            document = json.dumps(
                {"case": case.model_dump(mode="json"), "vectors": vectors}, ensure_ascii=False
            )
            self._collection.upsert(
                ids=[str(case.caseId)],
                documents=[document],
                embeddings=[vectors[0]],
                metadatas=[{"house_id": str(case.houseId), "case_version": case.caseVersion}],
            )

    def search(self, request: SimilarCasesRequest) -> list[dict]:
        with self._locked():
            if not request.candidates:
                return []
            indexed = self._collection.get(
                ids=[str(case.caseId) for case in request.candidates],
                include=["documents"],
            )
            snapshots = dict(zip(indexed["ids"], indexed["documents"]))
            candidates = []
            for expected in request.candidates:
                if str(expected.caseId) not in snapshots:
                    raise IndexUnavailable("Requested case has not been indexed")
                record = json.loads(snapshots[str(expected.caseId)])
                actual = ConfirmedCaseContext.model_validate(record["case"])
                if canonical_case(actual) != canonical_case(expected):
                    raise IndexUnavailable("Index snapshot is stale")
                if len(record["vectors"]) != len(actual.items):
                    raise IndexUnavailable("Incomplete indexed snapshot")
                candidates.append((actual, record["vectors"]))
        # All index records were read consistently. Java rechecks current DB versions and access after HTTP.
        query_vectors = self._vectors(request.query)
        results = []
        for candidate, vectors in candidates:
            pairs = matching_items(request.query, candidate, query_vectors, vectors)
            if not pairs:
                continue
            score = min(pair[0] for pair in pairs)
            full_match = len(pairs) == len(request.query.items) == len(candidate.items)
            results.append(
                {
                    "caseId": str(candidate.caseId),
                    "caseVersion": candidate.caseVersion,
                    "sourceRevision": candidate.sourceRevision,
                    "relation": "POSSIBLE_DUPLICATE"
                    if full_match and score >= DUPLICATE_THRESHOLD
                    else "RELATED",
                    "score": score,
                    "matchedItems": [pair[1] for pair in pairs],
                }
            )
        return sorted(results, key=lambda result: (-result["score"], result["caseId"]))[: request.limit]
