from dataclasses import dataclass
from typing import Any

import chromadb


@dataclass(frozen=True)
class SimilarityQuery:
    text: str
    house_id: str | None = None
    exclude_case_id: str | None = None
    query_item_id: str | None = None


class ChromaVectorStore:
    """Small adapter around Chroma, kept behind an infrastructure boundary."""

    def __init__(self, path: str, collection: str, enabled: bool = True) -> None:
        self._client: Any | None = None
        self._collection: Any | None = None
        if enabled:
            self._client = chromadb.PersistentClient(path=path)
            self._collection = self._client.get_or_create_collection(name=collection)

    @property
    def enabled(self) -> bool:
        return self._collection is not None

    def add(self, *, document_id: str, text: str, metadata: dict[str, str] | None = None) -> None:
        if self._collection is None:
            return
        self._collection.upsert(ids=[document_id], documents=[text], metadatas=[metadata or {}])

    def search(
        self,
        query: SimilarityQuery,
        limit: int = 5,
    ) -> list[dict[str, Any]]:
        if self._collection is None:
            return []
        if self._collection.count() == 0:
            return []
        where = {"house_id": query.house_id} if query.house_id else None
        result = self._collection.query(
            query_texts=[query.text],
            n_results=min(limit * 5, self._collection.count()),
            where=where,
        )
        candidates = []
        seen_cases = set()
        for metadata, distance in zip(result.get("metadatas", [[]])[0], result.get("distances", [[]])[0]):
            if metadata.get("case_id") == query.exclude_case_id:
                continue
            if metadata.get("case_id") in seen_cases or len(candidates) >= limit:
                continue
            seen_cases.add(metadata["case_id"])
            score = 1 / (1 + distance)
            candidates.append(
                {
                    "caseId": metadata["case_id"],
                    "caseVersion": int(metadata["case_version"]),
                    "sourceRevision": int(metadata["source_revision"]),
                    "relation": "POSSIBLE_DUPLICATE" if score >= 0.5 else "RELATED",
                    "score": score,
                    "matchedItems": (
                        [{"queryItemId": query.query_item_id, "candidateItemId": metadata["item_id"]}]
                        if query.query_item_id
                        else []
                    ),
                }
            )
        return candidates

    def upsert_case(self, event: dict[str, Any]) -> None:
        if self._collection is None:
            return
        case = event["case"]
        existing = self._collection.get(where={"case_id": str(case["caseId"])}, include=[])
        if existing.get("ids"):
            self._collection.delete(ids=existing["ids"])
        ids = []
        documents = []
        metadatas = []
        for item in case["items"]:
            ids.append(f"{case['caseId']}:{item['itemId']}")
            documents.append(f"{item['category']} {item['location']} {item['symptom']} {case['sourceText']}")
            metadatas.append(
                {
                    "case_id": str(case["caseId"]),
                    "house_id": str(case["houseId"]),
                    "case_version": str(case["caseVersion"]),
                    "source_revision": str(case["sourceRevision"]),
                    "item_id": str(item["itemId"]),
                    "content_version": str(item["contentVersion"]),
                    "work_round": str(item["workRound"]),
                }
            )
        if ids:
            self._collection.upsert(ids=ids, documents=documents, metadatas=metadatas)
