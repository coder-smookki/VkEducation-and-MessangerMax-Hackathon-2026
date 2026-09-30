package ru.koren.cases.application;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** One audit event as the case's participants may see it: allow-listed fields only, never the raw payload. */
public record HistoryEvent(UUID eventId, UUID actorId, String eventType, Instant createdAt,
                           Map<String, Object> details) {
    public HistoryEvent {
        details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }
}
