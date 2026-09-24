package ru.koren.cases.application;

import java.time.Instant;
import java.util.UUID;

/** Body of PUT /items/{itemId}/next-action. Versions come from the last response, never invented by the client. */
public record NextActionRequest(long expectedCaseVersion, long expectedItemVersion, String text, UUID ownerId,
                                Instant updateDueAt) {}
