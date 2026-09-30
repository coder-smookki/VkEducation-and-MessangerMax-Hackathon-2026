package ru.koren.cases.application;

/** Confirmation pins both the aggregate version and the exact source revision being accepted. */
public record ConfirmRequest(long expectedCaseVersion, int sourceRevision) {}
