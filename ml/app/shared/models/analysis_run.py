from datetime import datetime
from uuid import UUID, uuid4

from sqlalchemy import JSON, DateTime, String, text
from sqlalchemy.orm import Mapped, mapped_column

from app.shared.models.base import Base


class AnalysisRun(Base):
    """ML audit record; it never owns case/report business state."""

    __tablename__ = "analysis_runs"

    id: Mapped[UUID] = mapped_column(primary_key=True, default=uuid4)
    case_id: Mapped[UUID] = mapped_column(index=True)
    report_id: Mapped[UUID] = mapped_column(index=True)
    source_revision: Mapped[int]
    model_version: Mapped[str] = mapped_column(String(200))
    mode: Mapped[str] = mapped_column(String(32))
    response: Mapped[dict] = mapped_column(JSON)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=text("CURRENT_TIMESTAMP")
    )
