from enum import StrEnum
from typing import Annotated
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, model_validator


class Contract(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class Category(StrEnum):
    LIGHTING = "LIGHTING"
    ENTRANCE_DOOR = "ENTRANCE_DOOR"
    CLEANING = "CLEANING"
    OTHER = "OTHER"


class Relation(StrEnum):
    REPORTED_DONE = "REPORTED_DONE"
    PLANNED = "PLANNED"
    SYMPTOM_PERSISTS = "SYMPTOM_PERSISTS"
    INSUFFICIENT_INFORMATION = "INSUFFICIENT_INFORMATION"
    UNRELATED = "UNRELATED"
    CONFLICT = "CONFLICT"


class Mode(StrEnum):
    STUB = "STUB"
    RULES = "RULES"
    NLI = "NLI"
    MANUAL = "MANUAL"


class Reason(StrEnum):
    MODEL_NOT_CONFIGURED = "MODEL_NOT_CONFIGURED"
    ANALYSIS_UNAVAILABLE = "ANALYSIS_UNAVAILABLE"
    ANALYZED = "ANALYZED"


PositiveVersion = Annotated[int, Field(strict=True, ge=1)]
WorkRound = Annotated[int, Field(strict=True, ge=0)]


class Item(Contract):
    itemId: UUID
    contentVersion: PositiveVersion
    workRound: WorkRound
    category: Category
    location: str = Field(min_length=1, max_length=300, pattern=r"\S")
    symptom: str = Field(min_length=1, max_length=1000, pattern=r"\S")


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


class Evidence(Contract):
    # Unicode code points. Python str offsets match this; Java/JS must convert from UTF-16.
    start: int = Field(strict=True, ge=0)
    end: int = Field(strict=True, ge=1)
    quote: str = Field(min_length=1, max_length=5000)

    @model_validator(mode="after")
    def ordered_span(self):
        if self.end <= self.start or self.end - self.start != len(self.quote):
            raise ValueError("Invalid evidence span")
        return self


class Suggestion(Contract):
    itemId: UUID
    contentVersion: PositiveVersion
    workRound: WorkRound
    relation: Relation
    evidence: Evidence | None
    reason: Reason

    @model_validator(mode="after")
    def require_evidence(self):
        if self.relation != Relation.INSUFFICIENT_INFORMATION and self.evidence is None:
            raise ValueError("A substantive relation requires evidence")
        return self


class CompareResponse(Contract):
    caseId: UUID
    sourceRevision: PositiveVersion
    reportId: UUID
    modelVersion: str = Field(min_length=1, max_length=200)
    mode: Mode
    suggestions: list[Suggestion] = Field(min_length=1, max_length=20)
