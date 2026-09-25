package ru.koren.reports.application;

import java.time.Instant;
import java.util.UUID;

/** A dispatcher-confirmed link: this report concerns this item, at this content version, in this round. */
public record LinkView(UUID itemId, int contentVersion, int workRound, String workStatus,
                       UUID confirmedBy, Instant createdAt) {}
