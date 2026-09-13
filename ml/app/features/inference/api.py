from dishka import FromDishka
from dishka.integrations.fastapi import DishkaRoute, inject
from fastapi import APIRouter

from app.features.inference.schemas import CompareRequest, CompareResponse
from app.features.inference.service import InferenceService
from app.http_errors import BodySizeError, CompareInputError
from app.shared.database import Database

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
    database: FromDishka[Database],
) -> CompareResponse:
    async with database.session_factory() as session:
        return await service.compare(request, session)
