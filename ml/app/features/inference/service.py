from app.core.config import Settings
from app.features.inference.rules_analyzer import RULES_MODEL_VERSION, analyze_rules
from app.features.inference.schemas import (
    CompareRequest,
    CompareResponse,
    Mode,
    Reason,
    Relation,
    Suggestion,
)
from app.shared.vector_store import ChromaVectorStore

STUB_MODEL_VERSION = "stub-v1"


def active_mode(settings: Settings | None) -> tuple[Mode, str]:
    """The mode compare really runs in. Health reports this, so the two can never disagree."""
    if settings is not None and settings.compare_analyzer == "rules":
        return Mode.RULES, RULES_MODEL_VERSION
    return Mode.STUB, STUB_MODEL_VERSION


class InferenceService:
    def __init__(self, vector_store: ChromaVectorStore, settings: Settings | None = None) -> None:
        self.vector_store = vector_store
        self.mode, _ = active_mode(settings)

    @staticmethod
    def analyze(request: CompareRequest) -> CompareResponse:
        """Return an advisory result without changing product state."""
        return CompareResponse(
            caseId=request.caseId,
            sourceRevision=request.sourceRevision,
            reportId=request.reportId,
            modelVersion=STUB_MODEL_VERSION,
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
        if self.mode == Mode.RULES:
            return analyze_rules(request)
        return self.analyze(request)
