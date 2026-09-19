from app.core.config import Settings
from app.features.inference.rules_analyzer import analyze_rules
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
    def __init__(self, vector_store: ChromaVectorStore, settings: Settings | None = None) -> None:
        self.vector_store = vector_store
        self.compare_analyzer = settings.compare_analyzer if settings else "stub"

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
        if self.compare_analyzer == "rules":
            return analyze_rules(request)
        return self.analyze(request)
