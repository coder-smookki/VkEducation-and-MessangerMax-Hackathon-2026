package ru.koren.max.application;

import java.util.UUID;

/**
 * Transport only. Throwing means "not delivered": the notification stays queued for another try.
 * A real sender throws MaxDeliveryException so the outbox can tell a rate limit from a refusal;
 * any other exception counts as MAX being temporarily unavailable.
 */
public interface MaxSender {
    void send(UUID recipientId, String eventType, String payload);
}
