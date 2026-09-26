package ru.koren.observations.application;

/** Body of POST /items/{itemId}/observations. NOT_RECEIVED is the absence of an answer, not a value. */
public record ObservationRequest(long expectedCaseVersion, long expectedItemVersion, int contentVersion,
                                 int workRound, String value, String source) {}
