package ru.koren.cases.application;

/** Acceptance pins the case version the dispatcher saw; it carries no status or role. */
public record AcceptRequest(long expectedCaseVersion) {}
