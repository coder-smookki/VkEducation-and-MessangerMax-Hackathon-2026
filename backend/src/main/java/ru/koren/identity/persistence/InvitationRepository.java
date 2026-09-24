package ru.koren.identity.persistence;

import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Invitations are consumed by conditional UPDATE only: the check and the write must be one statement. */
public final class InvitationRepository {

    private final JdbcTemplate jdbc;

    public InvitationRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Returns the house of the invitation this user just consumed, or null when nothing was consumable. */
    public UUID consume(String codeHash, UUID userId) {
        var houses = jdbc.query("""
                UPDATE house_invitation
                SET consumed_at = now(), consumed_by = ?
                WHERE code_hash = ?
                  AND consumed_at IS NULL
                  AND expires_at > now()
                  AND failed_attempts < max_attempts
                RETURNING house_id
                """,
            (rs, rowNum) -> rs.getObject("house_id", UUID.class), userId, codeHash);
        return houses.isEmpty() ? null : houses.getFirst();
    }

    /** Counts a wrong or late attempt so that guessing runs out of tries. */
    public void countFailedAttempt(String codeHash) {
        jdbc.update("""
                UPDATE house_invitation SET failed_attempts = failed_attempts + 1
                WHERE code_hash = ? AND consumed_at IS NULL AND failed_attempts < max_attempts
                """, codeHash);
    }
}
