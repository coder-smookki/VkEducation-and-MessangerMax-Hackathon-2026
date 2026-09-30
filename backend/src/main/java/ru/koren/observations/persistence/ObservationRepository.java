package ru.koren.observations.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** History of the author's answers. The current projection lives on case_item; this table keeps every round. */
public class ObservationRepository {

    private final JdbcTemplate jdbc;

    public ObservationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insertObservation(UUID id, UUID itemId, UUID caseId, UUID authorId, int contentVersion,
                                  int workRound, String value, String source) {
        jdbc.update("""
                INSERT INTO resident_observation(id, item_id, case_id, author_id, content_version, work_round, value, 
                source)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, itemId, caseId, authorId, contentVersion, workRound, value, source);
    }
}
