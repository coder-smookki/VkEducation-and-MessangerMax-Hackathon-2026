package ru.koren.max.persistence;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** The inbox is the ACK boundary: MAX is answered only after this row is committed. */
public class InboxRepository {

    public record InboxRow(UUID id, String eventKey, String payload, int attempts) {}

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

    /**
     * Claims pending events under a lease owned by leaseToken. FOR UPDATE SKIP LOCKED lets a second
     * worker take other rows instead of waiting, and the lease releases the row by itself if this
     * worker dies. The token is what lets a finishing worker prove the row is still its own.
     */
    public List<InboxRow> claim(Timestamp now, int leaseSeconds, int maxAttempts, int limit,
                                UUID leaseToken) {
        return jdbc.query("""
                UPDATE inbox_event
                   SET leased_until = ?::timestamptz + make_interval(secs => ?),
                       leased_by = ?, attempts = attempts + 1
                 WHERE id IN (
                       SELECT id FROM inbox_event
                        WHERE processed_at IS NULL
                          AND available_at <= ?
                          AND (leased_until IS NULL OR leased_until <= ?)
                          AND attempts < ?
                        ORDER BY available_at, id
                          FOR UPDATE SKIP LOCKED
                        LIMIT ?)
             RETURNING id, event_key, payload::text AS payload, attempts
                """, (rs, row) -> new InboxRow(rs.getObject("id", UUID.class),
                rs.getString("event_key"), rs.getString("payload"), rs.getInt("attempts")),
            now, leaseSeconds, leaseToken, now, now, maxAttempts, limit);
    }

    /** Zero rows means the lease was lost while we worked: another worker owns this event now. */
    public int markProcessed(UUID id, UUID leaseToken) {
        return jdbc.update("""
                UPDATE inbox_event SET processed_at = now(), leased_until = NULL, leased_by = NULL
                 WHERE id = ? AND leased_by = ?
                """, id, leaseToken);
    }

    /** Releases a failed event for a later attempt; attempts was already raised by the claim. */
    public int release(UUID id, Timestamp availableAt, UUID leaseToken) {
        return jdbc.update("""
                UPDATE inbox_event SET leased_until = NULL, leased_by = NULL, available_at = ?
                 WHERE id = ? AND leased_by = ?
                """, availableAt, id, leaseToken);
    }
}
