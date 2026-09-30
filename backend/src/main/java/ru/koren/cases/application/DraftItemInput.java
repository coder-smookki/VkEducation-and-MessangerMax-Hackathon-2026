package ru.koren.cases.application;

import java.util.UUID;

/** One slot of a full draft-list replacement. itemId null means the server assigns a new persisted id. */
public record DraftItemInput(UUID itemId, String category, String location, String symptom) {}
