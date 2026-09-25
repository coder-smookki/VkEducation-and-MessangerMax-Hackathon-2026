package ru.koren.reports.persistence;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** SQL for work reports and their dispatcher-confirmed links. */
public class ReportRepository {

    private final JdbcTemplate jdbc;

    public ReportRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record ReportRow(UUID id, UUID caseId, UUID actorId, String reportText, Timestamp createdAt) {}

    public record LinkRow(UUID itemId, int contentVersion, int workRound, String workStatus,
                          UUID confirmedBy, Timestamp createdAt) {}

    public void insertReport(UUID id, UUID caseId, UUID actorId, String reportText) {
        jdbc.update("INSERT INTO work_report(id, case_id, actor_id, report_text) VALUES (?, ?, ?, ?)",
            id, caseId, actorId, reportText);
    }

    public void insertTargets(UUID reportId, UUID caseId, List<UUID> itemIds) {
        jdbc.batchUpdate("INSERT INTO report_target_item(report_id, item_id, case_id) VALUES (?, ?, ?)",
            itemIds.stream().map(itemId -> new Object[]{reportId, itemId, caseId}).toList());
    }

    public Optional<ReportRow> findReport(UUID reportId) {
        return jdbc.query("SELECT id, case_id, actor_id, report_text, created_at FROM work_report WHERE id = ?",
            (rs, row) -> new ReportRow(rs.getObject("id", UUID.class), rs.getObject("case_id", UUID.class),
                rs.getObject("actor_id", UUID.class), rs.getString("report_text"),
                rs.getTimestamp("created_at")), reportId).stream().findFirst();
    }

    public List<UUID> findTargets(UUID reportId) {
        return jdbc.query("SELECT item_id FROM report_target_item WHERE report_id = ? ORDER BY item_id",
            (rs, row) -> rs.getObject("item_id", UUID.class), reportId);
    }

    public List<LinkRow> findLinks(UUID reportId) {
        return jdbc.query("""
                SELECT item_id, content_version, work_round, work_status, confirmed_by, created_at
                FROM report_item_link WHERE report_id = ? ORDER BY created_at, item_id
                """, (rs, row) -> new LinkRow(rs.getObject("item_id", UUID.class), rs.getInt("content_version"),
            rs.getInt("work_round"), rs.getString("work_status"),
            rs.getObject("confirmed_by", UUID.class), rs.getTimestamp("created_at")), reportId);
    }

    /** How many of these ids are items of this case: a target list must not reach outside the case. */
    public int countItemsInCase(UUID caseId, List<UUID> itemIds) {
        if (itemIds.isEmpty()) return 0;
        String placeholders = String.join(",", java.util.Collections.nCopies(itemIds.size(), "?"));
        Object[] args = new Object[itemIds.size() + 1];
        args[0] = caseId;
        for (int i = 0; i < itemIds.size(); i++) args[i + 1] = itemIds.get(i);
        return jdbc.queryForObject(
            "SELECT count(*) FROM case_item WHERE case_id = ? AND id IN (" + placeholders + ")",
            Integer.class, args);
    }

    /** Checked before inserting: the primary key would throw a DuplicateKeyException that runCommand
     *  mistakes for a race over the idempotency record, turning a 409 into a 500. */
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
}
