"""Business services for intake analysis and Chroma-backed retrieval."""

import logging

from app.features.inference.schemas import Mode, Reason
from app.features.intake.contracts import (
    AnalysisStatus,
    AuthorQuestion,
    ClarifyItemsRequest,
    ClarifyItemsResponse,
    ExtractItemsRequest,
    ExtractItemsResponse,
    ItemClarification,
    MissingField,
    Readiness,
    SimilarCasesRequest,
    SimilarCasesResponse,
)
from app.shared.vector_store import MODEL_VERSION, ChromaVectorStore

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


def similar_cases(request: SimilarCasesRequest, vector_store: ChromaVectorStore) -> SimilarCasesResponse:
    try:
        results = vector_store.search(request)
    # Libraries use unrelated exception types; this advisory boundary logs no resident text.
    except Exception as error:  # noqa: BLE001  # pylint: disable=broad-exception-caught
        logging.getLogger(__name__).warning("Similarity unavailable: %s", type(error).__name__)
        return SimilarCasesResponse(
            caseId=request.query.caseId,
            caseVersion=request.query.caseVersion,
            sourceRevision=request.query.sourceRevision,
            modelVersion="unavailable",
            mode=Mode.MANUAL,
            status=AnalysisStatus.NOT_ANALYZED,
            reason=Reason.ANALYSIS_UNAVAILABLE,
            scope="CHROMA",
            results=[],
        )
    return SimilarCasesResponse(
        caseId=request.query.caseId,
        caseVersion=request.query.caseVersion,
        sourceRevision=request.query.sourceRevision,
        modelVersion=MODEL_VERSION,
        mode=Mode.RULES,
        status=AnalysisStatus.ANALYZED,
        reason=Reason.ANALYZED,
        scope="CHROMA",
        results=results,
    )
