from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.analyzer import compare
from app.contracts import CompareRequest, CompareResponse

app = FastAPI(title="KOREN inference", version="0.1.0")


@app.exception_handler(RequestValidationError)
async def invalid_request(_request: Request, _exception: RequestValidationError):
    # FastAPI's default detail contains input values. Do not echo residents' texts or secret-like input.
    return JSONResponse(status_code=422, content={"detail": "INVALID_COMPARE_REQUEST"})


@app.get("/healthz")
def health() -> dict[str, str]:
    return {"status": "ok", "mode": "STUB"}


@app.post("/internal/v1/compare", response_model=CompareResponse)
def compare_report(request: CompareRequest) -> CompareResponse:
    return compare(request)
