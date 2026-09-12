"""Replace this abstaining stub in task N2 after baseline evaluation. No model is loaded."""

from app.contracts import CompareRequest, CompareResponse, Mode, Reason, Relation, Suggestion


def compare(request: CompareRequest) -> CompareResponse:
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
