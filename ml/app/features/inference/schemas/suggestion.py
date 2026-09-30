from uuid import UUID

from pydantic import model_validator

from app.features.inference.schemas.enums import Reason, Relation
from app.features.inference.schemas.evidence import Evidence
from app.features.inference.schemas.item import PositiveVersion, WorkRound
from app.shared.schemas import Contract


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
