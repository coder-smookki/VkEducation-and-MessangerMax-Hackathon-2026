package ru.koren.cases.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ru.koren.cases.domain.CaseProgress;

/** Author/dispatcher projection of a case. progress is always derived via CaseProgress.from. */
public record CaseView(UUID caseId, UUID houseId, UUID authorId, String submissionStatus, long caseVersion,
                       int sourceRevision, String sourceText, String displayNumber, CaseReceipt receipt,
                       String visibility, List<CaseItemView> items, CaseProgress progress, Instant createdAt) {
    public CaseView {
        items = List.copyOf(items);
    }
}
