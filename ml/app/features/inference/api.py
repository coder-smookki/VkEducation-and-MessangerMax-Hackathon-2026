from dishka import FromDishka
from dishka.integrations.fastapi import DishkaRoute, inject
from fastapi import APIRouter

from app.features.inference.schemas import CompareRequest, CompareResponse
from app.features.inference.service import InferenceService
from app.http_errors import BodySizeError, CompareInputError

router = APIRouter(prefix="/internal/v1", tags=["inference"], route_class=DishkaRoute)


@router.post(
    "/compare",
    response_model=CompareResponse,
    responses={422: {"model": CompareInputError}, 413: {"model": BodySizeError}},
)
@inject
async def compare_report(
    request: CompareRequest,
    service: FromDishka[InferenceService],
) -> CompareResponse:
    return service.compare(request)
