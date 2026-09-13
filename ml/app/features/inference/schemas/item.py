from typing import Annotated
from uuid import UUID

from pydantic import Field

from app.features.inference.schemas.enums import Category
from app.shared.schemas import Contract

PositiveVersion = Annotated[int, Field(strict=True, ge=1)]
WorkRound = Annotated[int, Field(strict=True, ge=0)]


class Item(Contract):
    itemId: UUID
    contentVersion: PositiveVersion
    workRound: WorkRound
    category: Category
    location: str = Field(min_length=1, max_length=300, pattern=r"\S")
    symptom: str = Field(min_length=1, max_length=1000, pattern=r"\S")
