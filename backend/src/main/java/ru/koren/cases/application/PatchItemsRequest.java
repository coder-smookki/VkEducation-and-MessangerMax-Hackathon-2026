package ru.koren.cases.application;

import java.util.List;

/** Full draft-list replacement: a missing item is removed, not kept. */
public record PatchItemsRequest(long expectedCaseVersion, List<DraftItemInput> items) {}
