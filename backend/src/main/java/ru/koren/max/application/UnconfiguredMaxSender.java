package ru.koren.max.application;

import java.util.UUID;

/**
 * Stands in until the MAX Bot API call is written against the real service. It refuses rather than
 * reporting success: a queue that marks undelivered notifications as sent is worse than an empty one,
 * because the table stops being evidence. Delivery receipts cannot come from a stub.
 */
public class UnconfiguredMaxSender implements MaxSender {

    @Override
    public void send(UUID recipientId, String eventType, String payload) {
        throw new UnsupportedOperationException("MAX transport is not configured");
    }
}
