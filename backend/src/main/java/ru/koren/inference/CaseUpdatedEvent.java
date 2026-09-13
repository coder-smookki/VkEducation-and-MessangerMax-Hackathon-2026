package ru.koren.inference;

import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Set;
import static ru.koren.inference.IntakeContract.ConfirmedCaseContext;

/** Versioned event consumed by ML; the snapshot is produced only from Java-owned state. */
public record CaseUpdatedEvent(UUID eventId, String eventType, int schemaVersion,
                               @JsonProperty("case") ConfirmedCaseContext caseSnapshot) {
    public CaseUpdatedEvent {
        if (eventId == null || !Set.of("CASE_CREATED", "CASE_UPDATED").contains(eventType)
                || schemaVersion < 1 || caseSnapshot == null)
            throw new IllegalArgumentException("Invalid case update event");
    }
}
