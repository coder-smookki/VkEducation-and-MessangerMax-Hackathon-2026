from sqlalchemy.ext.asyncio import AsyncSession

from app.features.inference.repository import AnalysisRunRepository
from app.features.inference.schemas import (
    CompareRequest,
    CompareResponse,
    Mode,
    Reason,
    Relation,
    Suggestion,
)


class InferenceService:
    def __init__(self, repository: AnalysisRunRepository) -> None:
        self.repository = repository

    @staticmethod
    def analyze(request: CompareRequest) -> CompareResponse:
        """Return an advisory result without changing product state."""
        return CompareResponse(
            caseId=request.caseId,
            sourceRevision=request.sourceRevision,
            reportId=request.reportId,
            modelVersion="stub-v1",
            mode=Mode.STUB,
            suggestions=[
                Suggestion(
                    itemId=item.itemId,
                    contentVersion=item.contentVersion,
                    workRound=item.workRound,
                    relation=Relation.INSUFFICIENT_INFORMATION,
                    evidence=None,
                    reason=Reason.MODEL_NOT_CONFIGURED,
                )
                for item in request.items
            ],
        )

    async def compare(self, request: CompareRequest, session: AsyncSession) -> CompareResponse:
        response = self.analyze(request)
        await self.repository.save(session, response)
        await session.commit()
        return response
