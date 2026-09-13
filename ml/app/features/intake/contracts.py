"""Intake contracts. Java supplies authorized snapshots; these types grant no permissions."""

from enum import StrEnum
from typing import Annotated, Literal
from uuid import UUID

from pydantic import Field, model_validator

from app.features.inference.schemas import Category, Evidence, Item, Mode, PositiveVersion, Reason
from app.shared.schemas import Contract


class AnalysisStatus(StrEnum):
    NOT_ANALYZED = "NOT_ANALYZED"
    ANALYZED = "ANALYZED"


class DraftReference(Contract):
    caseId: UUID
    caseVersion: PositiveVersion
    sourceRevision: PositiveVersion


class DraftItem(Contract):
    # Temporary slot in this draft, never a persisted case_item.id.
    proposalId: Annotated[int, Field(strict=True, ge=1, le=20)]
    category: Category | None
    location: Annotated[str, Field(min_length=1, max_length=300, pattern=r"\S")] | None
    symptom: Annotated[str, Field(min_length=1, max_length=1000, pattern=r"\S")] | None


class ProposedItem(DraftItem):
    evidence: Evidence


class ExtractItemsRequest(DraftReference):
    sourceText: str = Field(min_length=1, max_length=5000, pattern=r"\S")


class ExtractItemsResponse(DraftReference):
    modelVersion: str = Field(min_length=1, max_length=200, pattern=r"\S")
    mode: Mode
    status: AnalysisStatus
    reason: Reason
    proposals: list[ProposedItem] = Field(max_length=20)

    @model_validator(mode="after")
    def honest_result(self):
        if len({item.proposalId for item in self.proposals}) != len(self.proposals):
            raise ValueError("Duplicate proposalId")
        if self.mode in (Mode.STUB, Mode.MANUAL):
            expected = Reason.MODEL_NOT_CONFIGURED if self.mode == Mode.STUB else Reason.ANALYSIS_UNAVAILABLE
            if self.status != AnalysisStatus.NOT_ANALYZED or self.proposals or self.reason != expected:
                raise ValueError("Unavailable extraction must abstain")
        elif self.status != AnalysisStatus.ANALYZED or self.reason != Reason.ANALYZED:
            raise ValueError("Analyzed extraction requires an explicit outcome")
        return self


class ClarifyItemsRequest(DraftReference):
    items: list[DraftItem] = Field(min_length=1, max_length=20)

    @model_validator(mode="after")
    def unique_items(self):
        if len({item.proposalId for item in self.items}) != len(self.items):
            raise ValueError("Duplicate proposalId")
        return self


class MissingField(StrEnum):
    CATEGORY = "category"
    LOCATION = "location"
    SYMPTOM = "symptom"


class Readiness(StrEnum):
    NEEDS_DETAILS = "NEEDS_DETAILS"
    READY_FOR_REVIEW = "READY_FOR_REVIEW"


class AuthorQuestion(Contract):
    field: MissingField
    text: str = Field(min_length=1, max_length=300, pattern=r"\S")


class ItemClarification(Contract):
    proposalId: Annotated[int, Field(strict=True, ge=1, le=20)]
    readiness: Readiness
    questions: list[AuthorQuestion] = Field(max_length=3)

    @model_validator(mode="after")
    def consistent_readiness(self):
        if len({q.field for q in self.questions}) != len(self.questions):
            raise ValueError("Duplicate question")
        expected = Readiness.NEEDS_DETAILS if self.questions else Readiness.READY_FOR_REVIEW
        if self.readiness != expected:
            raise ValueError("Readiness must describe missing fields, not completed repair")
        return self


class ClarifyItemsResponse(DraftReference):
    modelVersion: Literal["required-fields-v1"]
    mode: Literal["RULES"]
    recipient: Literal["AUTHOR"]
    items: list[ItemClarification] = Field(min_length=1, max_length=20)

    @model_validator(mode="after")
    def unique_items(self):
        if len({item.proposalId for item in self.items}) != len(self.items):
            raise ValueError("Duplicate proposalId")
        return self


class ConfirmedCaseContext(DraftReference):
    houseId: UUID
    submissionStatus: Literal["CONFIRMED"]
    sourceText: str = Field(min_length=1, max_length=5000, pattern=r"\S")
    items: list[Item] = Field(min_length=1, max_length=20)

    @model_validator(mode="after")
    def unique_items(self):
        if len({item.itemId for item in self.items}) != len(self.items):
            raise ValueError("Duplicate itemId")
        return self


class SimilarCasesRequest(Contract):
    query: ConfirmedCaseContext
    limit: int = Field(default=5, strict=True, ge=1, le=20)


class SimilarityRelation(StrEnum):
    POSSIBLE_DUPLICATE = "POSSIBLE_DUPLICATE"
    RELATED = "RELATED"
    DIFFERENT = "DIFFERENT"
    NOT_EVALUATED = "NOT_EVALUATED"


class ItemMatch(Contract):
    queryItemId: UUID
    candidateItemId: UUID


class SimilarCaseResult(DraftReference):
    relation: SimilarityRelation
    score: Annotated[float, Field(strict=True, ge=0, le=1, allow_inf_nan=False)] | None
    matchedItems: list[ItemMatch] = Field(max_length=20)

    @model_validator(mode="after")
    def honest_score(self):
        pairs = [(item.queryItemId, item.candidateItemId) for item in self.matchedItems]
        if len(set(pairs)) != len(pairs):
            raise ValueError("Duplicate match")
        if self.relation == SimilarityRelation.NOT_EVALUATED:
            if self.score is not None or self.matchedItems:
                raise ValueError("Not evaluated is not a zero similarity score")
        elif self.score is None:
            raise ValueError("Evaluated relation requires a score")
        if self.relation in (SimilarityRelation.POSSIBLE_DUPLICATE, SimilarityRelation.RELATED) and not pairs:
            raise ValueError("A suggested relationship must identify matching items")
        if self.relation == SimilarityRelation.DIFFERENT and pairs:
            raise ValueError("Different cases cannot contain proposed matches")
        return self


class SimilarCasesResponse(DraftReference):
    modelVersion: str = Field(min_length=1, max_length=200, pattern=r"\S")
    mode: Mode
    status: AnalysisStatus
    reason: Reason
    scope: Literal["CHROMA"]
    results: list[SimilarCaseResult] = Field(max_length=20)

    @model_validator(mode="after")
    def honest_result(self):
        if len({result.caseId for result in self.results}) != len(self.results):
            raise ValueError("Duplicate candidate result")
        if self.mode in (Mode.STUB, Mode.MANUAL):
            expected = Reason.MODEL_NOT_CONFIGURED if self.mode == Mode.STUB else Reason.ANALYSIS_UNAVAILABLE
            if self.status != AnalysisStatus.NOT_ANALYZED or self.reason != expected:
                raise ValueError("Unavailable similarity must abstain")
            if any(result.relation != SimilarityRelation.NOT_EVALUATED for result in self.results):
                raise ValueError("Unavailable similarity cannot claim duplicates or their absence")
        elif self.status != AnalysisStatus.ANALYZED or self.reason != Reason.ANALYZED:
            raise ValueError("Analyzed similarity requires an explicit outcome")
        return self
