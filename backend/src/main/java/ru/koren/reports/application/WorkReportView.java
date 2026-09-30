package ru.koren.reports.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Immutable report text plus the links a dispatcher has confirmed for it. */
public record WorkReportView(UUID reportId, UUID caseId, UUID actorId, String reportText,
                             List<UUID> targetItemIds, Instant createdAt, List<LinkView> links) {}
