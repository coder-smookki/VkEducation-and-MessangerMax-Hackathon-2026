package ru.koren.notifications.persistence;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Durable delivery queue. A row is written in the same transaction as the state it announces. */
public class OutboxRepository {

    public record OutboxRow(UUID id, UUID caseId, UUID recipientId, String eventType,
                            String payload, int attempts) {}

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** False means this deduplication_key is already queued: the same news, not a second one. */
    public boolean enqueue(String deduplicationKey, UUID caseId, UUID recipientId,
                           String eventType, String payloadJson) {
        return jdbc.update("""
                INSERT INTO outbox_event(id, case_id, recipient_id, event_type, deduplication_key, payload)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (deduplication_key) DO NOTHING
                """, UUID.randomUUID(), caseId, recipientId, eventType, deduplicationKey, payloadJson) == 1;
    }

    /** A reply to the person who just wrote to the bot: it belongs to them, not to a case. */
    public boolean enqueueReply(String deduplicationKey, UUID recipientId, String eventType,
                                String payloadJson) {
        return jdbc.update("""
                INSERT INTO outbox_event(id, case_id, recipient_id, event_type, deduplication_key, payload)
                VALUES (?, NULL, ?, ?, ?, ?::jsonb)
                ON CONFLICT (deduplication_key) DO NOTHING
                """, UUID.randomUUID(), recipientId, eventType, deduplicationKey, payloadJson) == 1;
    }

    public List<OutboxRow> claim(Timestamp now, int leaseSeconds, int maxAttempts, int limit) {
        return jdbc.query("""
                UPDATE outbox_event
                   SET leased_until = ?::timestamptz + make_interval(secs => ?), attempts = attempts + 1
                 WHERE id IN (
                       SELECT id FROM outbox_event
                        WHERE sent_at IS NULL
                          AND available_at <= ?
                          AND (leased_until IS NULL OR leased_until <= ?)
                          AND attempts < ?
                        ORDER BY available_at, id
                          FOR UPDATE SKIP LOCKED
                        LIMIT ?)
             RETURNING id, case_id, recipient_id, event_type, payload::text AS payload, attempts
                """, (rs, row) -> new OutboxRow(rs.getObject("id", UUID.class),
                rs.getObject("case_id", UUID.class), rs.getObject("recipient_id", UUID.class),
                rs.getString("event_type"), rs.getString("payload"), rs.getInt("attempts")),
            now, leaseSeconds, now, now, maxAttempts, limit);
    }

    public void markSent(UUID id) {
        jdbc.update("UPDATE outbox_event SET sent_at = now(), leased_until = NULL, last_error = NULL"
            + " WHERE id = ?", id);
    }

    /** Keeps the reason next to the row: a parked notification must explain itself without logs. */
    public void release(UUID id, Timestamp availableAt, String error) {
        jdbc.update("UPDATE outbox_event SET leased_until = NULL, available_at = ?, last_error = ?"
            + " WHERE id = ?", availableAt, error == null ? null
            : error.substring(0, Math.min(error.length(), 500)), id);
    }

    /** Drops a notification whose recipient may no longer read the case; the reason stays visible. */
    public void cancel(UUID id, String reason) {
        jdbc.update("UPDATE outbox_event SET leased_until = NULL,"
            + " available_at = now() + interval '100 years', last_error = ? WHERE id = ?", reason, id);
    }
}
