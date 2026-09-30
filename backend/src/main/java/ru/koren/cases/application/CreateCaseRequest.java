package ru.koren.cases.application;

import java.util.UUID;

/** Author and role never come from the body: only the target house and the raw text do. */
public record CreateCaseRequest(UUID houseId, String sourceText) {}
