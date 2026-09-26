package ru.koren.max.application;

import java.util.UUID;

/** Transport only. Throwing means "not delivered": the notification stays queued for another try. */
public interface MaxSender {
    void send(UUID recipientId, String eventType, String payload);
}
