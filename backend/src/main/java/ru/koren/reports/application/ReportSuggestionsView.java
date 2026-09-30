package ru.koren.reports.application;

import java.util.UUID;
import ru.koren.inference.InferenceContract;

/**
 * GET /reports/{reportId}/suggestions. result is present only for a current READY analysis.
 * READY means a valid suggestion, never that work was done: links are still the dispatcher's decision.
 */
public record ReportSuggestionsView(String status, UUID analysisId, InferenceContract.Response result) {}
