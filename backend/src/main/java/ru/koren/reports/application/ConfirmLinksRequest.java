package ru.koren.reports.application;

import java.util.List;

/** Body of POST /reports/{reportId}/links. The whole set applies atomically or not at all. */
public record ConfirmLinksRequest(long expectedCaseVersion, List<LinkInput> links) {}
