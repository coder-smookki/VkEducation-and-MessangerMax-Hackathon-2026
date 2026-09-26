package ru.koren.max.persistence;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.koren.max.application.HttpMaxSender;

/** Reads what the sender needs and nothing more: the MAX id of a user and the number of a case. */
public class JdbcMaxRecipients implements HttpMaxSender.Recipients {

    private final JdbcTemplate jdbc;

    public JdbcMaxRecipients(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public OptionalLong maxUserId(UUID userId) {
        List<Long> ids = jdbc.query("SELECT max_user_id FROM app_user WHERE id = ?",
            (rs, row) -> rs.getObject(1, Long.class), userId);
        // A user created another way (a seeded dispatcher) may have no MAX id at all.
        return ids.isEmpty() || ids.getFirst() == null ? OptionalLong.empty() : OptionalLong.of(ids.getFirst());
    }

    @Override
    public Optional<Long> displayNumber(UUID caseId) {
        return jdbc.query("SELECT display_number FROM service_case WHERE id = ?",
            (rs, row) -> rs.getLong(1), caseId).stream().findFirst();
    }
}
