package ru.koren.persistence;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S-07 / QA-R10: a database that already holds cases, reports, observations and audit at V2 is
 * migrated to the current version. Every other test starts from an empty schema and never proves this.
 * Runs in its own throw-away schema, so the shared test database is never touched.
 */
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class MigrationUpgradeTest {

    private static final String URL = System.getenv("DB_TEST_URL");
    private static final String USER = envOr("DB_TEST_USER", "koren");
    private static final String PASSWORD = envOr("DB_TEST_PASSWORD", "koren_local_only");

    private final UUID author = UUID.randomUUID();
    private final UUID dispatcher = UUID.randomUUID();
    private final UUID house = UUID.randomUUID();
    private final UUID caseId = UUID.randomUUID();
    private final UUID light = UUID.randomUUID();
    private final UUID door = UUID.randomUUID();
    private final UUID report = UUID.randomUUID();

    private String schema;
    private JdbcTemplate jdbc;

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(URL, USER, PASSWORD).schemas(schema).createSchemas(true);
        if (target != null) config.target(target);
        return config.load();
    }

    @BeforeEach
    void schemaAtV2() {
        schema = "migration_check_" + UUID.randomUUID().toString().replace("-", "");
        flyway("2").migrate();
        var dataSource = new DriverManagerDataSource(
            URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + schema, USER, PASSWORD);
        jdbc = new JdbcTemplate(dataSource);
        // One transaction: case and revision point at each other through a deferred key.
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO app_user(id, max_user_id) VALUES (?, 1001), (?, 1002)", author, dispatcher);
            jdbc.update("INSERT INTO house(id, address) VALUES (?, 'Миграционная, 2')", house);
            jdbc.update("INSERT INTO membership VALUES (?, ?, 'RESIDENT'), (?, ?, 'DISPATCHER')",
                author, house, dispatcher, house);
            jdbc.update("INSERT INTO service_case(id, house_id, author_id, version, submission_status)"
                + " VALUES (?, ?, ?, 5, 'CONFIRMED')", caseId, house, author);
            jdbc.update("INSERT INTO case_revision(case_id, revision, source_text)"
                + " VALUES (?, 1, 'Темно на третьем этаже, дверь не закрывается')", caseId);
            jdbc.update("INSERT INTO case_item(id, case_id, version, work_round, work_status, observation)"
                    + " VALUES (?, ?, 3, 1, 'REPORTED_DONE', 'RESTORED'), (?, ?, 1, 0, 'NOT_ASSIGNED', 'NOT_RECEIVED')",
                light, caseId, door, caseId);
            jdbc.update("INSERT INTO item_revision(item_id, content_version, category, location, symptom, confirmed_at)"
                + " VALUES (?, 1, 'LIGHTING', 'Этаж 3', 'Не горит свет', now()),"
                + " (?, 1, 'ENTRANCE_DOOR', 'Вход', 'Дверь не закрывается', now())", light, door);
            jdbc.update("INSERT INTO work_report(id, case_id, actor_id, report_text)"
                + " VALUES (?, ?, ?, 'Лампу заменили')", report, caseId, dispatcher);
            jdbc.update("INSERT INTO report_item_link(report_id, item_id, case_id, content_version, work_round,"
                    + " work_status, confirmed_by) VALUES (?, ?, ?, 1, 1, 'REPORTED_DONE', ?)",
                report, light, caseId, dispatcher);
            jdbc.update("INSERT INTO resident_observation(id, item_id, case_id, author_id, content_version,"
                    + " work_round, value, source) VALUES (?, ?, ?, ?, 1, 1, 'RESTORED', 'AFTER_REPORT')",
                UUID.randomUUID(), light, caseId, author);
            jdbc.update("INSERT INTO model_suggestion(id, case_id, report_id, source_revision, model_version, payload)"
                + " VALUES (?, ?, ?, 1, 'stub-v1', '{\"mode\":\"STUB\"}')", UUID.randomUUID(), caseId, report);
            for (String type : List.of("CASE_CREATED", "CASE_CONFIRMED", "REPORT_SAVED", "LINKS_CONFIRMED")) {
                jdbc.update("INSERT INTO audit_event(id, case_id, actor_id, event_type, payload)"
                        + " VALUES (?, ?, ?, ?, ?::jsonb)", UUID.randomUUID(), caseId, author, type,
                    "{\"caseId\":\"" + caseId + "\",\"type\":\"" + type + "\"}");
            }
            jdbc.update("INSERT INTO outbox_event(id, case_id, recipient_id, event_type, deduplication_key, payload,"
                    + " attempts) VALUES (?, ?, ?, 'LINK_CONFIRMED', 'migration-check-outbox', '{\"caseId\":\"x\"}', 2)",
                UUID.randomUUID(), caseId, author);
            jdbc.update("INSERT INTO inbox_event(id, event_key, payload_hash, payload, attempts)"
                    + " VALUES (?, 'migration-check-inbox', repeat('a', 64), '{\"update_type\":\"message_created\"}', 1)",
                UUID.randomUUID());
            jdbc.update("INSERT INTO processed_command(actor_id, operation, idempotency_key, request_hash,"
                + " response_status, response_body) VALUES (?, 'CONFIRM', 'migration-check-key', repeat('b', 64),"
                + " 200, '{\"caseId\":\"x\"}')", author);
        });
    }

    @AfterEach
    void dropSchema() {
        if (jdbc != null) jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    private static final List<String> TABLES = List.of("app_user", "house", "membership", "service_case",
        "case_revision", "case_item", "item_revision", "work_report", "report_item_link", "resident_observation",
        "model_suggestion", "audit_event", "outbox_event", "inbox_event", "processed_command");

    private Map<String, Long> counts() {
        var counts = new java.util.LinkedHashMap<String, Long>();
        TABLES.forEach(table -> counts.put(table, jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class)));
        return counts;
    }

    private List<String> auditPayloads() {
        return jdbc.queryForList("SELECT event_type || ' ' || payload::text FROM audit_event ORDER BY event_type",
            String.class);
    }

    @Test
    void aV2DatabaseWithDataReachesTheCurrentVersionLosingNothing() {
        var countsBefore = counts();
        var auditBefore = auditPayloads();
        String replayBefore = jdbc.queryForObject(
            "SELECT response_body::text FROM processed_command WHERE idempotency_key = 'migration-check-key'",
            String.class);

        var result = flyway(null).migrate();

        assertTrue(result.success, "every migration applies on top of real rows");
        assertEquals(0, flyway(null).info().pending().length, "nothing is left unapplied");
        assertEquals(countsBefore, counts(), "no row appears or disappears in any old table");
        assertEquals(auditBefore, auditPayloads(), "audit survives byte for byte");
        assertEquals(replayBefore, jdbc.queryForObject(
            "SELECT response_body::text FROM processed_command WHERE idempotency_key = 'migration-check-key'",
            String.class), "a stored command result still replays the same answer");

        // The state of the work is untouched: the round, the status and the author's answer.
        var lightRow = jdbc.queryForMap("SELECT work_round, work_status, observation, version, content_version"
            + " FROM case_item WHERE id = ?", light);
        assertEquals(1, lightRow.get("work_round"));
        assertEquals("REPORTED_DONE", lightRow.get("work_status"));
        assertEquals("RESTORED", lightRow.get("observation"));
        assertEquals(3L, lightRow.get("version"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM report_item_link WHERE item_id = ?"
            + " AND work_round = 1", Integer.class, light), "the confirmed link keeps its round");

        // Columns added later describe facts that did not happen before them.
        var caseRow = jdbc.queryForMap("SELECT submission_status, version, accepted_at, display_number"
            + " FROM service_case WHERE id = ?", caseId);
        assertEquals("CONFIRMED", caseRow.get("submission_status"));
        assertEquals(5L, caseRow.get("version"));
        assertNull(caseRow.get("accepted_at"), "an old case was never accepted by the new command");
        assertNotNull(caseRow.get("display_number"), "an old case still gets a number for the screens");
        assertEquals(List.of(1, 2), jdbc.queryForList(
                "SELECT position FROM case_item WHERE case_id = ? ORDER BY position", Integer.class, caseId),
            "old items get a stable order");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM membership WHERE revoked_at IS NOT NULL",
            Integer.class), "nobody loses access by being migrated");

        // Queued work is still queued: a migration must not deliver, drop or re-lease anything.
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
            "SELECT sent_at IS NULL AND attempts = 2 FROM outbox_event WHERE deduplication_key = 'migration-check-outbox'",
            Boolean.class));
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
            "SELECT processed_at IS NULL AND attempts = 1 FROM inbox_event WHERE event_key = 'migration-check-inbox'",
            Boolean.class));
    }
}
