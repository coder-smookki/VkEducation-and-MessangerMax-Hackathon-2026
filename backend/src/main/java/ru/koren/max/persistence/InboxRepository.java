package ru.koren.max.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

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
}
