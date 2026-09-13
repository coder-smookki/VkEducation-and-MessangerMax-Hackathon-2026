from typing import Any

import chromadb


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

    def search(self, query: str, limit: int = 5) -> dict[str, Any]:
        if self._collection is None:
            return {"ids": [[]], "documents": [[]], "metadatas": [[]]}
        return self._collection.query(query_texts=[query], n_results=limit)
