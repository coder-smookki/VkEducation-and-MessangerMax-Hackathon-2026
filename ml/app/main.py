from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.analyzer import compare
from app.contracts import CompareRequest, CompareResponse
from app.http_errors import BodySizeError, CompareInputError, IntakeInputError, RequestSizeLimit
from app.intake import clarify_items, extract_items, similar_cases
from app.intake_contracts import (
    ClarifyItemsRequest,
    ClarifyItemsResponse,
    ExtractItemsRequest,
    ExtractItemsResponse,
    SimilarCasesRequest,
    SimilarCasesResponse,
)

app = FastAPI(title="KOREN inference", version="0.2.0")
app.add_middleware(RequestSizeLimit)
INTAKE_ERRORS = {422: {"model": IntakeInputError}, 413: {"model": BodySizeError}}


@app.exception_handler(RequestValidationError)
async def invalid_request(request: Request, _exception: RequestValidationError):
    # FastAPI's default detail contains input values. Do not echo residents' texts or secret-like input.
    detail = "INVALID_COMPARE_REQUEST" if request.url.path == "/internal/v1/compare" else "INVALID_INTAKE_REQUEST"
    return JSONResponse(status_code=422, content={"detail": detail})


@app.get("/healthz")
def health() -> dict[str, str]:
    return {"status": "ok", "mode": "STUB"}


@app.post("/internal/v1/compare", response_model=CompareResponse,
          responses={422: {"model": CompareInputError}, 413: {"model": BodySizeError}})
def compare_report(request: CompareRequest) -> CompareResponse:
    return compare(request)


@app.post("/internal/v1/extract-items", response_model=ExtractItemsResponse, responses=INTAKE_ERRORS)
def extract(request: ExtractItemsRequest) -> ExtractItemsResponse:
    return extract_items(request)


@app.post("/internal/v1/clarify-items", response_model=ClarifyItemsResponse, responses=INTAKE_ERRORS)
def clarify(request: ClarifyItemsRequest) -> ClarifyItemsResponse:
    return clarify_items(request)


@app.post("/internal/v1/similar-cases", response_model=SimilarCasesResponse, responses=INTAKE_ERRORS)
def similar(request: SimilarCasesRequest) -> SimilarCasesResponse:
    return similar_cases(request)
