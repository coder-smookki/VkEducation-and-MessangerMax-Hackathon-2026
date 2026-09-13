from uuid import UUID

from pydantic import Field, model_validator

from app.features.inference.schemas.enums import Mode
from app.features.inference.schemas.item import Item, PositiveVersion
from app.features.inference.schemas.suggestion import Suggestion
from app.shared.schemas import Contract


class CompareRequest(Contract):
    caseId: UUID
    sourceRevision: PositiveVersion
    reportId: UUID
    reportText: str = Field(min_length=1, max_length=5000, pattern=r"\S")
    items: list[Item] = Field(min_length=1, max_length=20)

    @model_validator(mode="after")
    def unique_items(self):
        if len({item.itemId for item in self.items}) != len(self.items):
            raise ValueError("Duplicate itemId")
        return self


class CompareResponse(Contract):
    caseId: UUID
    sourceRevision: PositiveVersion
    reportId: UUID
    modelVersion: str = Field(min_length=1, max_length=200)
    mode: Mode
    suggestions: list[Suggestion] = Field(min_length=1, max_length=20)
