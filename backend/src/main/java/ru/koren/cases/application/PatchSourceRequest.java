package ru.koren.cases.application;

/** Draft text replacement guarded by the case version the client last saw. */
public record PatchSourceRequest(long expectedCaseVersion, String sourceText) {}
