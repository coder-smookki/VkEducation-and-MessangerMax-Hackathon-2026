package ru.koren.cases.persistence;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Explicit SQL only; no JPA. Every statement runs inside the caller's transaction unless noted otherwise. */
@Repository
@Profile("!no-db")
public class CaseRepository {
    private final JdbcTemplate jdbc;

    public CaseRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record CaseRow(UUID id, UUID houseId, UUID authorId, int sourceRevision, long version,
                          String submissionStatus, Timestamp createdAt) {
        public boolean isDraft() { return "DRAFT".equals(submissionStatus); }
    }

    /** Current projection joined with the current content revision. */
    public record ItemRow(UUID id, int contentVersion, long version, int workRound, String workStatus,
                          String observation, String administrativeStatus, String administrativeReason,
                          String category, String location, String symptom) {}

    public record ProcessedRow(String requestHash, int responseStatus, String responseBody) {}

    /** Keyset position for the stable (createdAt DESC, id DESC) ordering. */
    public record Cursor(Timestamp createdAt, UUID id) {}

    public Optional<String> membershipRole(UUID userId, UUID houseId) {
        return jdbc.query("SELECT role FROM membership WHERE user_id = ? AND house_id = ?",
                (rs, row) -> rs.getString("role"), userId, houseId).stream().findFirst();
    }

    public Optional<CaseRow> findCase(UUID caseId) {
        return jdbc.query("""
                SELECT id, house_id, author_id, source_revision, version, submission_status, created_at
                FROM service_case WHERE id = ?
                """, (rs, row) -> mapCase(rs), caseId).stream().findFirst();
    }

    public String currentSourceText(UUID caseId, int revision) {
        return jdbc.queryForObject(
                "SELECT source_text FROM case_revision WHERE case_id = ? AND revision = ?",
                String.class, caseId, revision);
    }

    public List<ItemRow> findItems(UUID caseId) {
        return jdbc.query("""
                SELECT i.id, i.content_version, i.version, i.work_round, i.work_status, i.observation,
                       i.administrative_status, i.administrative_reason, r.category, r.location, r.symptom
                FROM case_item i
                JOIN item_revision r ON r.item_id = i.id AND r.content_version = i.content_version
                WHERE i.case_id = ? ORDER BY i.id
                """, (rs, row) -> new ItemRow(rs.getObject("id", UUID.class), rs.getInt("content_version"),
                rs.getLong("version"), rs.getInt("work_round"), rs.getString("work_status"),
                rs.getString("observation"), rs.getString("administrative_status"),
                rs.getString("administrative_reason"), rs.getString("category"),
                rs.getString("location"), rs.getString("symptom")), caseId);
    }

    /** Own cases of any status plus confirmed cases of houses where the actor is a dispatcher. */
    public List<CaseRow> listVisibleCases(UUID actorId, UUID houseId, Cursor after, int limit) {
        String visibility = """
                SELECT c.id, c.house_id, c.author_id, c.source_revision, c.version, c.submission_status, c.created_at
                FROM service_case c
                WHERE (CAST(? AS uuid) IS NULL OR c.house_id = CAST(? AS uuid))
                  AND (c.author_id = ? OR (c.submission_status = 'CONFIRMED' AND EXISTS (
                        SELECT 1 FROM membership m
                        WHERE m.user_id = ? AND m.house_id = c.house_id AND m.role = 'DISPATCHER')))
                """;
        if (after == null) {
            return jdbc.query(visibility + " ORDER BY c.created_at DESC, c.id DESC LIMIT ?",
                    (rs, row) -> mapCase(rs), houseId, houseId, actorId, actorId, limit);
        }
        return jdbc.query(visibility + """
                   AND (c.created_at, c.id) < (CAST(? AS timestamptz), CAST(? AS uuid))
                ORDER BY c.created_at DESC, c.id DESC LIMIT ?
                """, (rs, row) -> mapCase(rs), houseId, houseId, actorId, actorId,
                after.createdAt(), after.id(), limit);
    }

    private static CaseRow mapCase(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CaseRow(rs.getObject("id", UUID.class), rs.getObject("house_id", UUID.class),
                rs.getObject("author_id", UUID.class), rs.getInt("source_revision"), rs.getLong("version"),
                rs.getString("submission_status"), rs.getTimestamp("created_at"));
    }

    public void insertCase(UUID id, UUID houseId, UUID authorId) {
        // Membership FK (author_id, house_id) is immediate; the current-revision FK is deferred to commit.
        jdbc.update("INSERT INTO service_case(id, house_id, author_id) VALUES (?, ?, ?)", id, houseId, authorId);
    }

    public void insertCaseRevision(UUID caseId, int revision, String sourceText) {
        jdbc.update("INSERT INTO case_revision(case_id, revision, source_text) VALUES (?, ?, ?)",
                caseId, revision, sourceText);
    }

    public void updateSourceRevision(UUID caseId, int revision) {
        jdbc.update("UPDATE service_case SET source_revision = ? WHERE id = ?", revision, caseId);
    }

    /** Optimistic lock of the aggregate: one winner per expected version, drafts only. */
    public int bumpDraftCaseVersion(UUID caseId, long expectedVersion) {
        return jdbc.update("""
                UPDATE service_case SET version = version + 1
                WHERE id = ? AND version = ? AND submission_status = 'DRAFT'
                """, caseId, expectedVersion);
    }

    public void markCaseConfirmed(UUID caseId) {
        jdbc.update("UPDATE service_case SET submission_status = 'CONFIRMED' WHERE id = ?", caseId);
    }

    public void insertItem(UUID itemId, UUID caseId) {
        jdbc.update("INSERT INTO case_item(id, case_id) VALUES (?, ?)", itemId, caseId);
    }

    public void insertItemRevision(UUID itemId, int contentVersion, String category, String location, String symptom) {
        jdbc.update("""
                INSERT INTO item_revision(item_id, content_version, category, location, symptom)
                VALUES (?, ?, ?, ?, ?)
                """, itemId, contentVersion, category, location, symptom);
    }

    public void updateItemContent(UUID itemId, int newContentVersion) {
        jdbc.update("UPDATE case_item SET content_version = ?, version = version + 1 WHERE id = ?",
                newContentVersion, itemId);
    }

    public int bumpAllItemVersions(UUID caseId) {
        return jdbc.update("UPDATE case_item SET version = version + 1 WHERE case_id = ?", caseId);
    }

    /** Stamps exactly the current revisions; returns the number of rows stamped. */
    public int confirmCurrentItemRevisions(UUID caseId) {
        return jdbc.update("""
                UPDATE item_revision r SET confirmed_at = now()
                FROM case_item i
                WHERE i.case_id = ? AND r.item_id = i.id AND r.content_version = i.content_version
                  AND r.confirmed_at IS NULL
                """, caseId);
    }

    public int countItemDependencies(UUID itemId) {
        Integer count = jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM report_item_link WHERE item_id = ?)
                     + (SELECT count(*) FROM resident_observation WHERE item_id = ?)
                """, Integer.class, itemId, itemId);
        return count == null ? 0 : count;
    }

    /** Draft-only removal: revisions first, then the projection row. */
    public void deleteItem(UUID itemId) {
        jdbc.update("DELETE FROM item_revision WHERE item_id = ?", itemId);
        jdbc.update("DELETE FROM case_item WHERE id = ?", itemId);
    }

    public void insertAudit(UUID caseId, UUID actorId, String eventType, String payloadJson) {
        jdbc.update("INSERT INTO audit_event(id, case_id, actor_id, event_type, payload) VALUES (?, ?, ?, ?, ?::jsonb)",
                UUID.randomUUID(), caseId, actorId, eventType, payloadJson);
    }

    public Optional<ProcessedRow> findProcessed(UUID actorId, String operation, String idempotencyKey) {
        return jdbc.query("""
                SELECT request_hash, response_status, response_body::text AS body
                FROM processed_command WHERE actor_id = ? AND operation = ? AND idempotency_key = ?
                """, (rs, row) -> new ProcessedRow(rs.getString("request_hash"), rs.getInt("response_status"),
                rs.getString("body")), actorId, operation, idempotencyKey).stream().findFirst();
    }

    public void insertProcessed(UUID actorId, String operation, String idempotencyKey,
                                String requestHash, int responseStatus, String responseBody) {
        jdbc.update("""
                INSERT INTO processed_command(actor_id, operation, idempotency_key, request_hash, response_status, response_body)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                """, actorId, operation, idempotencyKey, requestHash, responseStatus, responseBody);
    }
}
