"""Business services for intake analysis and Chroma-backed retrieval."""

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
from app.shared.vector_store import ChromaVectorStore, SimilarityQuery

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
    results = vector_store.search(
        query=SimilarityQuery(
            text=" ".join(f"{item.category} {item.location} {item.symptom}" for item in request.query.items),
            house_id=str(request.query.houseId),
            exclude_case_id=str(request.query.caseId),
            query_item_id=str(request.query.items[0].itemId),
        ),
        limit=request.limit,
    )
    return SimilarCasesResponse(
        caseId=request.query.caseId,
        caseVersion=request.query.caseVersion,
        sourceRevision=request.query.sourceRevision,
        modelVersion="chroma-retrieval-v1",
        mode=Mode.RULES,
        status=AnalysisStatus.ANALYZED,
        reason=Reason.ANALYZED,
        scope="CHROMA",
        results=results,
    )
