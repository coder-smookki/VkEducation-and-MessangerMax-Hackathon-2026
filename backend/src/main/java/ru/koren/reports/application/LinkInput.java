package ru.koren.reports.application;

import java.util.UUID;

/** One link a dispatcher confirms: three expected values plus what the report says about the work. */
public record LinkInput(UUID itemId, long expectedItemVersion, int contentVersion,
                        int expectedWorkRound, String workStatus) {}
