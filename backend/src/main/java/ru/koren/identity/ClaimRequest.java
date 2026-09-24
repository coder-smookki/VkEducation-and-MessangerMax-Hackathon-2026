package ru.koren.identity;

/** The code carries no role or house: the server resolves both from the stored invitation. */
public record ClaimRequest(String invitationCode) {}
