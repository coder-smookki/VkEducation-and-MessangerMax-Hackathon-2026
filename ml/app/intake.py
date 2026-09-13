"""Typed entry points for future analyzers. No weights, embeddings, database or external calls."""

from app.features.inference.schemas import Mode, Reason
from app.intake_contracts import (
    AnalysisStatus,
    AuthorQuestion,
    ClarifyItemsRequest,
    ClarifyItemsResponse,
    ExtractItemsRequest,
    ExtractItemsResponse,
    ItemClarification,
    MissingField,
    Readiness,
    SimilarCaseResult,
    SimilarCasesRequest,
    SimilarCasesResponse,
    SimilarityRelation,
)

QUESTIONS = {
    MissingField.CATEGORY: "Выберите тип проблемы: освещение, входная дверь, уборка или другое.",
    MissingField.LOCATION: "Где находится проблема? Уточните подъезд, этаж или вход.",
    MissingField.SYMPTOM: "Что именно не работает или требует исправления в этом месте?",
}


def extract_items(request: ExtractItemsRequest) -> ExtractItemsResponse:
    return ExtractItemsResponse(
        caseId=request.caseId,
        caseVersion=request.caseVersion,
        sourceRevision=request.sourceRevision,
        modelVersion="extraction-stub-v1",
        mode=Mode.STUB,
        status=AnalysisStatus.NOT_ANALYZED,
        reason=Reason.MODEL_NOT_CONFIGURED,
        proposals=[],
    )


def clarify_items(request: ClarifyItemsRequest) -> ClarifyItemsResponse:
    items = []
    for item in request.items:
        questions = [
            AuthorQuestion(field=field, text=text)
            for field, text in QUESTIONS.items()
            if getattr(item, field.value) is None
        ]
        items.append(
            ItemClarification(
                proposalId=item.proposalId,
                readiness=Readiness.NEEDS_DETAILS if questions else Readiness.READY_FOR_REVIEW,
                questions=questions,
            )
        )
    return ClarifyItemsResponse(
        caseId=request.caseId,
        caseVersion=request.caseVersion,
        sourceRevision=request.sourceRevision,
        modelVersion="required-fields-v1",
        mode="RULES",
        recipient="AUTHOR",
        items=items,
    )


def similar_cases(request: SimilarCasesRequest) -> SimilarCasesResponse:
    return SimilarCasesResponse(
        caseId=request.query.caseId,
        caseVersion=request.query.caseVersion,
        sourceRevision=request.query.sourceRevision,
        modelVersion="similarity-stub-v1",
        mode=Mode.STUB,
        status=AnalysisStatus.NOT_ANALYZED,
        reason=Reason.MODEL_NOT_CONFIGURED,
        scope="PROVIDED_CANDIDATES",
        results=[
            SimilarCaseResult(
                caseId=case.caseId,
                caseVersion=case.caseVersion,
                sourceRevision=case.sourceRevision,
                relation=SimilarityRelation.NOT_EVALUATED,
                score=None,
                matchedItems=[],
            )
            for case in request.candidates
        ],
    )
