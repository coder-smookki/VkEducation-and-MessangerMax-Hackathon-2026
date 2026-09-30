package ru.koren.persistence;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

/** Uses a real disposable PostgreSQL database. No H2 dialect substitution. */
@SpringBootTest(properties = {
        "spring.datasource.url=${DB_TEST_URL}",
        "spring.datasource.username=${DB_TEST_USER:koren}",
        "spring.datasource.password=${DB_TEST_PASSWORD:koren_local_only}"
})
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
@Transactional
class PostgresSchemaTest {
    @Autowired JdbcTemplate jdbc;
    private final UUID author = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final UUID house = UUID.randomUUID();
    private final UUID caseId = UUID.randomUUID();
    private final UUID otherCase = UUID.randomUUID();
    private final UUID item = UUID.randomUUID();

    @BeforeEach void setup() {
        jdbc.update("INSERT INTO app_user(id) VALUES (?), (?)", author, other);
        jdbc.update("INSERT INTO house(id, address) VALUES (?, 'Synthetic test house')", house);
        jdbc.update("INSERT INTO membership VALUES (?, ?, 'RESIDENT'), (?, ?, 'RESIDENT')", author, house, other, house);
        jdbc.update("INSERT INTO service_case(id, house_id, author_id) VALUES (?, ?, ?), (?, ?, ?)",
                caseId, house, author, otherCase, house, other);
        jdbc.update("INSERT INTO case_revision(case_id, revision, source_text) VALUES (?, 1, 'Lamp'), (?, 1, 'Door')", caseId, otherCase);
        jdbc.update("INSERT INTO case_item(id, case_id, position) VALUES (?, ?, 1)", item, caseId);
        jdbc.update("INSERT INTO item_revision(item_id, content_version, category, location, symptom) VALUES (?, 1, 'LIGHTING', 'Floor 3', 'Dark')", item);
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
    }
    @Test void migrationIsApplied() {
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class) >= 1);
    }
    @Test void otherResidentCannotOwnObservation() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO resident_observation(id,item_id,case_id,author_id,content_version,work_round,value,source)
                VALUES (?, ?, ?, ?, 1, 0, 'RESTORED', 'INDEPENDENT')
                """, UUID.randomUUID(), item, caseId, other));
    }
    @Test void reportCannotBeLinkedAcrossCases() {
        UUID report = UUID.randomUUID();
        jdbc.update("INSERT INTO work_report(id,case_id,actor_id,report_text) VALUES (?, ?, ?, 'Door fixed')", report, otherCase, other);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO report_item_link(report_id,item_id,case_id,content_version,work_round,work_status,confirmed_by)
                VALUES (?, ?, ?, 1, 1, 'REPORTED_DONE', ?)
                """, report, item, caseId, author));
    }
    @Test void archiveRequiresReason() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE case_item SET administrative_status='ARCHIVED', administrative_reason=NULL WHERE id=?", item));
    }
}
