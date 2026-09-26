package ru.koren.max.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Dialog state lives on the server: a MAX client must never be the source of truth about it. */
public class DialogRepository {

    public record DialogRow(UUID userId, String state, UUID caseId, UUID itemId,
                            Integer contentVersion, Integer workRound, long version) {}

    private final JdbcTemplate jdbc;

    public DialogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<DialogRow> find(UUID userId) {
        return jdbc.query("""
                SELECT user_id, state, case_id, item_id, content_version, work_round, version
                  FROM bot_dialog WHERE user_id = ?
                """, (rs, row) -> new DialogRow(rs.getObject("user_id", UUID.class),
                rs.getString("state"), rs.getObject("case_id", UUID.class),
                rs.getObject("item_id", UUID.class),
                (Integer) rs.getObject("content_version"), (Integer) rs.getObject("work_round"),
                rs.getLong("version")), userId)
            .stream().findFirst();
    }

    /** Idle is the only state that needs no context, so it is stored by clearing the rest. */
    public void setIdle(UUID userId) {
        jdbc.update("""
                INSERT INTO bot_dialog(user_id, state) VALUES (?, 'IDLE')
                ON CONFLICT (user_id) DO UPDATE
                   SET state = 'IDLE', case_id = NULL, item_id = NULL,
                       content_version = NULL, work_round = NULL,
                       version = bot_dialog.version + 1, updated_at = now()
                """, userId);
    }
}
