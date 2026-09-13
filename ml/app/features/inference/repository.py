from sqlalchemy.ext.asyncio import AsyncSession

from app.features.inference.schemas import CompareResponse
from app.shared.models.analysis_run import AnalysisRun


class AnalysisRunRepository:
    async def save(self, session: AsyncSession, response: CompareResponse) -> None:
        session.add(
            AnalysisRun(
                case_id=response.caseId,
                report_id=response.reportId,
                source_revision=response.sourceRevision,
                model_version=response.modelVersion,
                mode=response.mode.value,
                response=response.model_dump(mode="json"),
            )
        )
