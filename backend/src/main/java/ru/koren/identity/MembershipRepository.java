package ru.koren.identity;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads the current memberships of one user. Roles never come from the client. */
public final class MembershipRepository {

    private final JdbcTemplate jdbc;

    public MembershipRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public MeView loadMe(UUID userId, String displayName) {
        List<MembershipView> memberships = jdbc.query("""
            SELECT m.house_id, h.address, m.role
            FROM membership m
            JOIN house h ON h.id = m.house_id
            WHERE m.user_id = ?
            ORDER BY h.address, m.house_id
            """,
            (rs, rowNum) -> new MembershipView(
                rs.getObject("house_id", UUID.class), rs.getString("address"),
                rs.getString("role")), userId);
        return new MeView(userId, displayName, memberships);
    }
}
