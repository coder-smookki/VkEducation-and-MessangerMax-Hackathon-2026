from typing import Literal
from uuid import UUID

from pydantic import Field

from app.features.intake.contracts import ConfirmedCaseContext
from app.shared.schemas import Contract


class CaseUpdatedEvent(Contract):
    eventId: UUID
    eventType: Literal["CASE_CREATED", "CASE_UPDATED"]
    case: ConfirmedCaseContext
    schemaVersion: int = Field(strict=True, ge=1, le=1)
