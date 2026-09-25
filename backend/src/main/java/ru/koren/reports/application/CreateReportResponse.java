package ru.koren.reports.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.koren.cases.application.CaseView;

/** 201 body: the stored report, the unchanged case, and the analysis status. */
public record CreateReportResponse(WorkReportView report,
                                   @JsonProperty("case") CaseView caseView,
                                   AnalysisView analysis) {}
