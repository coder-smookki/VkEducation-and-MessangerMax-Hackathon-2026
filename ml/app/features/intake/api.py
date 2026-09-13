from fastapi import APIRouter

from app.features.intake.service import clarify_items, extract_items, similar_cases
from app.http_errors import BodySizeError, IntakeInputError
from app.intake_contracts import (
    ClarifyItemsRequest,
    ClarifyItemsResponse,
    ExtractItemsRequest,
    ExtractItemsResponse,
    SimilarCasesRequest,
    SimilarCasesResponse,
)

router = APIRouter(prefix="/internal/v1", tags=["intake"])
INTAKE_ERRORS = {422: {"model": IntakeInputError}, 413: {"model": BodySizeError}}


@router.post("/extract-items", response_model=ExtractItemsResponse, responses=INTAKE_ERRORS)
def extract(request: ExtractItemsRequest) -> ExtractItemsResponse:
    return extract_items(request)


@router.post("/clarify-items", response_model=ClarifyItemsResponse, responses=INTAKE_ERRORS)
def clarify(request: ClarifyItemsRequest) -> ClarifyItemsResponse:
    return clarify_items(request)


@router.post("/similar-cases", response_model=SimilarCasesResponse, responses=INTAKE_ERRORS)
def similar(request: SimilarCasesRequest) -> SimilarCasesResponse:
    return similar_cases(request)
