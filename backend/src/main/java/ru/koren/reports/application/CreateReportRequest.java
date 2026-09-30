package ru.koren.reports.application;

import java.util.List;
import java.util.UUID;

/** Body of POST /cases/{caseId}/reports. targetItemIds is the scope a link may later use, not a claim of work. */
public record CreateReportRequest(long expectedCaseVersion, String reportText, List<UUID> targetItemIds) {}
