package ru.koren.cases.persistence;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import org.springframework.jdbc.core.RowCallbackHandler;

/** Explicit SQL only; no JPA. Every statement runs inside the caller's transaction unless noted otherwise. */
@Repository
@Profile("!no-db")
public class CaseRepository {
    private final JdbcTemplate jdbc;

    public CaseRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record CaseRow(UUID id, UUID houseId, UUID authorId, int sourceRevision, long version,
                          String submissionStatus, Timestamp createdAt,
                          long displayNumber, Timestamp acceptedAt, UUID acceptedBy) {
        public boolean isDraft() { return "DRAFT".equals(submissionStatus); }
    }

    private static final String ITEM_SELECT = """
            SELECT i.case_id, i.id, i.content_version, i.version, i.work_round, i.work_status, i.observation,
                   i.administrative_status, i.administrative_reason, r.category, r.location, r.symptom,
                   i.next_action_text, i.next_action_owner_id,
                   i.next_action_update_due_at, i.next_action_updated_at
            FROM case_item i
            JOIN item_revision r ON r.item_id = i.id AND r.content_version = i.content_version
            """;

    private static ItemRow mapItem(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ItemRow(rs.getObject("id", UUID.class), rs.getInt("content_version"),
            rs.getLong("version"), rs.getInt("work_round"), rs.getString("work_status"),
            rs.getString("observation"), rs.getString("administrative_status"),
            rs.getString("administrative_reason"), rs.getString("category"),
            rs.getString("location"), rs.getString("symptom"),
            rs.getString("next_action_text"), rs.getObject("next_action_owner_id", UUID.class),
            rs.getTimestamp("next_action_update_due_at"), rs.getTimestamp("next_action_updated_at"));
    }

    /** Current projection joined with the current content revision. */
    public record ItemRow(UUID id, int contentVersion, long version, int workRound, String workStatus,
                          String observation, String administrativeStatus, String administrativeReason,
                          String category, String location, String symptom,
                          String nextActionText, UUID nextActionOwnerId,
                          Timestamp nextActionUpdateDueAt, Timestamp nextActionUpdatedAt) {}

    public record ProcessedRow(String requestHash, int responseStatus, String responseBody) {}

    /** Keyset position for the stable (createdAt DESC, id DESC) ordering. */
    public record Cursor(Timestamp createdAt, UUID id) {}

    public Optional<String> membershipRole(UUID userId, UUID houseId) {
        return jdbc.query("SELECT role FROM membership WHERE user_id = ? AND house_id = ? AND revoked_at IS NULL",
            (rs, rowNum) -> rs.getString(1), userId, houseId).stream().findFirst();
    }

    public Optional<CaseRow> findCase(UUID caseId) {
        return jdbc.query("""
                SELECT id, house_id, author_id, source_revision, version, submission_status, created_at,
                       display_number, accepted_at, accepted_by
                FROM service_case WHERE id = ?
                """,
            (rs, rowNum) -> new CaseRow(
                rs.getObject("id", UUID.class), rs.getObject("house_id", UUID.class),
                rs.getObject("author_id", UUID.class), rs.getInt("source_revision"),
                rs.getLong("version"), rs.getString("submission_status"), rs.getTimestamp("created_at"),
                rs.getLong("display_number"), rs.getTimestamp("accepted_at"),
                rs.getObject("accepted_by", UUID.class)),
            caseId).stream().findFirst();
    }

    public String currentSourceText(UUID caseId, int revision) {
        return jdbc.queryForObject(
                "SELECT source_text FROM case_revision WHERE case_id = ? AND revision = ?",
                String.class, caseId, revision);
    }

    public List<ItemRow> findItems(UUID caseId) {
        return jdbc.query(ITEM_SELECT + " WHERE i.case_id = ? ORDER BY i.position",
            (rs, row) -> mapItem(rs), caseId);
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    /** One query for the whole page: a dispatcher queue must not fan out into a query per case. */
    public Map<UUID, List<ItemRow>> findItemsByCase(List<UUID> caseIds) {
        if (caseIds.isEmpty()) return Map.of();
        Map<UUID, List<ItemRow>> byCase = new LinkedHashMap<>();
        jdbc.query(ITEM_SELECT + " WHERE i.case_id IN (" + placeholders(caseIds.size())
                + ") ORDER BY i.case_id, i.position",
            (RowCallbackHandler) rs -> byCase
                .computeIfAbsent(rs.getObject("case_id", UUID.class), key -> new ArrayList<>())
                .add(mapItem(rs)),
            caseIds.toArray());
        return byCase;
    }

    public Map<UUID, String> currentSourceTexts(List<UUID> caseIds) {
        if (caseIds.isEmpty()) return Map.of();
        Map<UUID, String> byCase = new LinkedHashMap<>();
        jdbc.query("SELECT c.id, r.source_text FROM service_case c"
                + " JOIN case_revision r ON r.case_id = c.id AND r.revision = c.source_revision"
                + " WHERE c.id IN (" + placeholders(caseIds.size()) + ")",
            (RowCallbackHandler) rs ->
                byCase.put(rs.getObject("id", UUID.class), rs.getString("source_text")),
            caseIds.toArray());
        return byCase;
    }

    public Map<UUID, Instant> confirmedAtByCase(List<UUID> caseIds) {
        if (caseIds.isEmpty()) return Map.of();
        Map<UUID, Instant> byCase = new LinkedHashMap<>();
        jdbc.query("SELECT i.case_id, max(r.confirmed_at) AS confirmed_at FROM case_item i"
                + " JOIN item_revision r ON r.item_id = i.id AND r.content_version = i.content_version"
                + " WHERE i.case_id IN (" + placeholders(caseIds.size()) + ") GROUP BY i.case_id",
            (RowCallbackHandler) rs -> {
                var stamp = rs.getTimestamp("confirmed_at");
                if (stamp != null) byCase.put(rs.getObject("case_id", UUID.class), stamp.toInstant());
            },
            caseIds.toArray());
        return byCase;
    }

    /** Everything a per-item command needs to authorize itself and check its context, in one round trip. */
    public record ItemContextRow(UUID itemId, long itemVersion, int contentVersion, int workRound,
                                 String workStatus, String observation, String administrativeStatus,
                                 UUID caseId, UUID houseId, long caseVersion, String submissionStatus) {
        public boolean isDraft() { return "DRAFT".equals(submissionStatus); }
    }

    public Optional<ItemContextRow> findItemContext(UUID itemId) {
        return jdbc.query("""
                SELECT i.id, i.version, i.content_version, i.work_round, i.work_status, i.observation,
                       i.administrative_status,
                       c.id AS case_id, c.house_id, c.version AS case_version, c.submission_status
                FROM case_item i
                JOIN service_case c ON c.id = i.case_id
                WHERE i.id = ?
                """, (rs, row) -> new ItemContextRow(rs.getObject("id", UUID.class), rs.getLong("version"),
            rs.getInt("content_version"), rs.getInt("work_round"), rs.getString("work_status"),
            rs.getString("observation"), rs.getString("administrative_status"),
            rs.getObject("case_id", UUID.class), rs.getObject("house_id", UUID.class),
            rs.getLong("case_version"), rs.getString("submission_status")), itemId).stream().findFirst();
    }

    /** Optimistic lock on the item row: zero rows means the caller held a stale itemVersion. */
    public int setNextAction(UUID itemId, long expectedItemVersion,
                             String text, UUID ownerId, Timestamp updateDueAt) {
        return jdbc.update("""
                UPDATE case_item
                   SET version = version + 1,
                       next_action_text = ?,
                       next_action_owner_id = ?,
                       next_action_update_due_at = ?,
                       next_action_updated_at = now()
                 WHERE id = ? AND version = ?
                """, text, ownerId, updateDueAt, itemId, expectedItemVersion);
    }

    /** Overlapping fact-based queue filters, not a new exclusive status; null means no filter. */
    private static String queuePredicate(String queueState) {
        if (queueState == null) return "";
        return switch (queueState) {
            case "NEEDS_ACCEPTANCE" -> " AND c.submission_status = 'CONFIRMED' AND c.accepted_at IS NULL";
            case "NEEDS_ACTION" -> """
                     AND c.accepted_at IS NOT NULL AND EXISTS (
                           SELECT 1 FROM case_item i WHERE i.case_id = c.id
                             AND i.administrative_status = 'ACTIVE' AND i.observation <> 'RESTORED'
                             AND (i.next_action_text IS NULL OR i.observation = 'PERSISTS'
                                  OR i.next_action_update_due_at < CAST(? AS timestamptz)))""";
            case "AWAITING_AUTHOR" -> """
                     AND EXISTS (SELECT 1 FROM case_item i WHERE i.case_id = c.id
                           AND i.administrative_status = 'ACTIVE' AND i.work_status = 'REPORTED_DONE'
                           AND i.observation IN ('NOT_RECEIVED', 'CANNOT_CHECK'))""";
            case "ARCHIVED" -> """
                     AND EXISTS (SELECT 1 FROM case_item i WHERE i.case_id = c.id
                           AND i.administrative_status = 'ARCHIVED')""";
            default -> throw new IllegalArgumentException("Unknown queue state: " + queueState);
        };
    }

    /** Own cases of any status plus confirmed cases of houses where the actor is a dispatcher. */
    public List<CaseRow> listVisibleCases(UUID actorId, UUID houseId, String queueState,
                                          Timestamp now, Cursor after, int limit) {
        String sql = """
                SELECT c.id, c.house_id, c.author_id, c.source_revision, c.version, c.submission_status, c.created_at,
                c.display_number, c.accepted_at, c.accepted_by
                FROM service_case c
                WHERE (CAST(? AS uuid) IS NULL OR c.house_id = CAST(? AS uuid))
                  AND (c.author_id = ? OR (c.submission_status = 'CONFIRMED' AND EXISTS (
                        SELECT 1 FROM membership m
                        WHERE m.user_id = ? AND m.house_id = c.house_id AND m.role = 'DISPATCHER'
                         AND m.revoked_at IS NULL )))
                """ + queuePredicate(queueState);
        var args = new ArrayList<Object>();
        args.add(houseId);
        args.add(houseId);
        args.add(actorId);
        args.add(actorId);
        if ("NEEDS_ACTION".equals(queueState)) args.add(now);
        if (after != null) {
            sql += "\n AND (c.created_at, c.id) < (CAST(? AS timestamptz), CAST(? AS uuid))";
            args.add(after.createdAt());
            args.add(after.id());
        }
        sql += "\n ORDER BY c.created_at DESC, c.id DESC LIMIT ?";
        args.add(limit);
        return jdbc.query(sql, (rs, row) -> mapCase(rs), args.toArray());
    }

    private static CaseRow mapCase(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CaseRow(rs.getObject("id", UUID.class), rs.getObject("house_id", UUID.class),
                rs.getObject("author_id", UUID.class), rs.getInt("source_revision"), rs.getLong("version"),
                rs.getString("submission_status"), rs.getTimestamp("created_at"),
                rs.getLong("display_number"), rs.getTimestamp("accepted_at"),
                rs.getObject("accepted_by", UUID.class));
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

    public void insertItem(UUID itemId, UUID caseId, int position) {
        jdbc.update("INSERT INTO case_item(id, case_id, position) VALUES (?, ?, ?)", itemId, caseId, position);
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

    public record AuditRow(UUID id, UUID actorId, String eventType, Timestamp createdAt, String payload) {}

    /** Ascending by (created_at, id): history is read forward, and the pair is unique and stable. */
    public List<AuditRow> listAudit(UUID caseId, Timestamp afterCreatedAt, UUID afterId, int limit) {
        var args = new java.util.ArrayList<Object>();
        args.add(caseId);
        String keyset = "";
        if (afterCreatedAt != null) {
            keyset = " AND (created_at, id) > (?, ?)";
            args.add(afterCreatedAt);
            args.add(afterId);
        }
        args.add(limit);
        return jdbc.query("""
                SELECT id, actor_id, event_type, created_at, payload::text AS payload
                  FROM audit_event
                 WHERE case_id = ?""" + keyset + """

                 ORDER BY created_at, id
                 LIMIT ?
                """, (rs, row) -> new AuditRow(rs.getObject("id", UUID.class),
                rs.getObject("actor_id", UUID.class), rs.getString("event_type"),
                rs.getTimestamp("created_at"), rs.getString("payload")),
            args.toArray());
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

    /** Reordering is not a content change: neither contentVersion nor version moves. */
    public void updateItemPosition(UUID itemId, int position) {
        jdbc.update("UPDATE case_item SET position = ? WHERE id = ?", position, itemId);
    }

    /** Optimistic lock for commands on a confirmed case: the draft-only guard does not apply here. */
    public int bumpConfirmedCaseVersion(UUID caseId, long expectedVersion) {
        return jdbc.update("""
                UPDATE service_case SET version = version + 1
                WHERE id = ? AND version = ? AND submission_status = 'CONFIRMED'
                """, caseId, expectedVersion);
    }

    /** Acceptance is recorded once: a repeat must not overwrite who accepted first. */
    public void acceptCase(UUID caseId, UUID dispatcherId) {
        jdbc.update("""
                UPDATE service_case SET accepted_at = now(), accepted_by = ?
                WHERE id = ? AND accepted_at IS NULL
                """, dispatcherId, caseId);
    }

    /** Confirmation time is already stored on the item revisions stamped by confirm. */
    public Instant confirmedAt(UUID caseId) {
        var times = jdbc.query("""
                SELECT max(r.confirmed_at) AS confirmed_at
                FROM case_item i JOIN item_revision r ON r.item_id = i.id AND r.content_version = i.content_version
                WHERE i.case_id = ?
                """, (rs, rowNum) -> rs.getTimestamp("confirmed_at"), caseId);
        var value = times.isEmpty() ? null : times.getFirst();
        return value == null ? null : value.toInstant();
    }

    /**
     * A confirmed link opens a new round for this item alone and resets its current observation.
     * Four guards in one statement: nothing about the item may have moved since the dispatcher looked.
     */
    public int openRoundForLink(UUID itemId, long expectedItemVersion, int expectedContentVersion,
                                int expectedWorkRound, String workStatus) {
        return jdbc.update("""
                UPDATE case_item
                   SET version = version + 1,
                       work_round = work_round + 1,
                       work_status = ?,
                       observation = 'NOT_RECEIVED'
                 WHERE id = ? AND version = ? AND content_version = ? AND work_round = ?
                """, workStatus, itemId, expectedItemVersion, expectedContentVersion, expectedWorkRound);
    }

    public boolean linkExists(UUID reportId, UUID itemId) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM report_item_link WHERE report_id = ? AND item_id = ?",
            Integer.class, reportId, itemId) > 0;
    }

    public void insertLink(UUID reportId, UUID itemId, UUID caseId, int contentVersion, int workRound,
                           String workStatus, UUID confirmedBy) {
        jdbc.update("""
                INSERT INTO report_item_link(report_id, item_id, case_id, content_version, work_round,
                                             work_status, confirmed_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, reportId, itemId, caseId, contentVersion, workRound, workStatus, confirmedBy);
    }

    /**
     * The author's answer for this exact round and content. Four guards: an answer to an old round
     * must not land on a new one.
     */
    public int applyObservation(UUID itemId, long expectedItemVersion, int expectedContentVersion,
                                int expectedWorkRound, String value) {
        return jdbc.update("""
                UPDATE case_item
                   SET version = version + 1, observation = ?
                 WHERE id = ? AND version = ? AND content_version = ? AND work_round = ?
                """, value, itemId, expectedItemVersion, expectedContentVersion, expectedWorkRound);
    }

    /** The ACTIVE guard lives in SQL as well: a second decision must never overwrite the first. */
    public int applyDecision(UUID itemId, long expectedItemVersion, String decision, String reason) {
        return jdbc.update("""
                UPDATE case_item
                   SET version = version + 1, administrative_status = ?, administrative_reason = ?
                 WHERE id = ? AND version = ? AND administrative_status = 'ACTIVE'
                """, decision, reason, itemId, expectedItemVersion);
    }
}
