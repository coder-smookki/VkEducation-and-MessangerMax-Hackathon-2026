from app.features.inference.schemas import (
    CompareRequest,
    CompareResponse,
    Mode,
    Reason,
    Relation,
    Suggestion,
)
from app.shared.vector_store import ChromaVectorStore


class InferenceService:
    def __init__(self, vector_store: ChromaVectorStore) -> None:
        self.vector_store = vector_store

    @staticmethod
    def analyze(request: CompareRequest) -> CompareResponse:
        """Return an advisory result without changing product state."""
        return CompareResponse(
            caseId=request.caseId,
            sourceRevision=request.sourceRevision,
            reportId=request.reportId,
            modelVersion="stub-v1",
            mode=Mode.STUB,
            suggestions=[
                Suggestion(
                    itemId=item.itemId,
                    contentVersion=item.contentVersion,
                    workRound=item.workRound,
                    relation=Relation.INSUFFICIENT_INFORMATION,
                    evidence=None,
                    reason=Reason.MODEL_NOT_CONFIGURED,
                )
                for item in request.items
            ],
        )

    def compare(self, request: CompareRequest) -> CompareResponse:
        return self.analyze(request)
