package ru.koren.cases.application;

import java.time.Instant;
import java.util.UUID;

/** Acceptance facts only: they never imply that any work happened. */
public record CaseReceipt(Instant confirmedAt, Instant acceptedAt, UUID acceptedBy) {}
