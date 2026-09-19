package ru.koren.identity;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Links a verified MAX user id to the internal app_user.id (find-or-create).
 * The unique index on max_user_id survives two simultaneous logins: on a unique
 * violation the existing row is re-read. The retry path must run outside an
 * aborted transaction (auto-commit per statement or a fresh transaction).
 */
public final class MaxUserProvisioner {

    private final JdbcTemplate jdbc;

    public MaxUserProvisioner(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public UUID findOrCreate(long maxUserId) {
        List<UUID> existing = jdbc.query(
                "SELECT id FROM app_user WHERE max_user_id = ?",
                (rs, rowNum) -> rs.getObject(1, UUID.class),
                maxUserId);
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO app_user(id, max_user_id) VALUES (?, ?)", id, maxUserId);
            return id;
        } catch (DuplicateKeyException concurrentLogin) {
            // A parallel login inserted the row first; read the winner's id.
            return jdbc.queryForObject(
                    "SELECT id FROM app_user WHERE max_user_id = ?", UUID.class, maxUserId);
        }
    }
}
