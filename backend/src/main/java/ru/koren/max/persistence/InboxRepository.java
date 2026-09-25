package ru.koren.max.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import java.util.List;


/** The inbox is the ACK boundary: MAX is answered only after this row is committed. */
public class InboxRepository {

    private final JdbcTemplate jdbc;

    public InboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** False means this event_key is already stored: a redelivery, not a new update. */
    public boolean accept(String eventKey, String payloadHash, String payloadJson) {
        return jdbc.update("""
                INSERT INTO inbox_event(id, event_key, payload_hash, payload)
                VALUES (?, ?, ?, ?::jsonb)
                ON CONFLICT (event_key) DO NOTHING
                """, UUID.randomUUID(), eventKey, payloadHash, payloadJson) == 1;
    }

    public record InboxRow(UUID id, String eventKey, String payload, int attempts) {}
    /**
     * Claims pending events under a lease. FOR UPDATE SKIP LOCKED lets a second worker take other
     * rows instead of waiting, and the lease releases the row by itself if this worker dies.
     */
    public List<InboxRow> claim(Timestamp now, int leaseSeconds, int maxAttempts, int limit) {
        return jdbc.query("""
                UPDATE inbox_event
                   SET leased_until = ?::timestamptz + make_interval(secs => ?), attempts = attempts + 1                 WHERE id IN (
                       SELECT id FROM inbox_event
                        WHERE processed_at IS NULL
                          AND available_at <= ?
                          AND (leased_until IS NULL OR leased_until <= ?)
                          AND attempts < ?
                        ORDER BY available_at, id
                          FOR UPDATE SKIP LOCKED
                        LIMIT ?)
             RETURNING id, event_key, payload::text AS payload, attempts                """, (rs, row) -> new InboxRow(rs.getObject("id", UUID.class),
                rs.getString("event_key"), rs.getString("payload"), rs.getInt("attempts")),
            now, leaseSeconds, now, now, maxAttempts, limit);
    }

    public void markProcessed(UUID id) {
        jdbc.update("UPDATE inbox_event SET processed_at = now(), leased_until = NULL WHERE id = ?", id);
    }

    /** Releases a failed event for a later attempt; attempts was already raised by the claim. */
    public void release(UUID id, Timestamp availableAt) {
        jdbc.update("UPDATE inbox_event SET leased_until = NULL, available_at = ? WHERE id = ?",
            availableAt, id);
    }
}
