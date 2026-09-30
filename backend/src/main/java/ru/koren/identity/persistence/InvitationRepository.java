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

    public record Grant(UUID houseId, String role) {}

    public Grant consume(String codeHash, UUID userId, boolean reviewInvitationsEnabled) {
        var houses = jdbc.query("""
                UPDATE house_invitation i
                SET consumed_at = now(), consumed_by = ?
                FROM house h
                WHERE i.house_id = h.id AND i.code_hash = ?
                  AND i.consumed_at IS NULL
                  AND i.expires_at > now()
                  AND i.failed_attempts < i.max_attempts
                  AND (i.granted_role = 'RESIDENT' OR (? AND h.review_only))
                RETURNING i.house_id, i.granted_role
                """,
            (rs, rowNum) -> new Grant(rs.getObject("house_id", UUID.class), rs.getString("granted_role")),
            userId, codeHash, reviewInvitationsEnabled);
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
