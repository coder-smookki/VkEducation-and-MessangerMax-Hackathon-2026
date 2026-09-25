package ru.koren.reports.application;

/** Analysis is a hint about the text, never a business status. MANUAL_REQUIRED is an honest answer. */
public record AnalysisView(String status) {}
