package ru.koren.cases.application;

/** Body of POST /items/{itemId}/administrative-decisions. Action is ARCHIVE or WITHDRAW; reason is required. */
public record DecisionRequest(long expectedCaseVersion, long expectedItemVersion, String action, String reason) {}
