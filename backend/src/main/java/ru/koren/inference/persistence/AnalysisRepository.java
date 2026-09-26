package ru.koren.inference.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** The analysis queue and its results. Queue time comes from the database clock, as in S-05. */
public class AnalysisRepository {

    public record ClaimedRow(UUID id, UUID reportId, UUID caseId, int sourceRevision, int attempts) {}

    public record AnalysisRow(UUID id, String status, UUID suggestionId, boolean leased) {}

    public record SuggestionRow(int sourceRevision, String payload) {}

    private final JdbcTemplate jdbc;

    public AnalysisRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Called inside the transaction that saves the report: both land or neither does. */
    public void enqueue(UUID reportId, UUID caseId, int sourceRevision) {
        jdbc.update("""
                INSERT INTO report_analysis(id, report_id, case_id, source_revision)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (report_id) DO NOTHING
                """, UUID.randomUUID(), reportId, caseId, sourceRevision);
    }

    public List<ClaimedRow> claim(int leaseSeconds, int maxAttempts, int limit, UUID leaseToken) {
        return jdbc.query("""
                UPDATE report_analysis
                   SET leased_until = now() + make_interval(secs => ?),
                       leased_by = ?, attempts = attempts + 1, updated_at = now()
                 WHERE id IN (
                       SELECT id FROM report_analysis
                        WHERE status = 'QUEUED'
                          AND available_at <= now()
                          AND (leased_until IS NULL OR leased_until <= now())
                          AND attempts < ?
                        ORDER BY available_at, id
                          FOR UPDATE SKIP LOCKED
                        LIMIT ?)
             RETURNING id, report_id, case_id, source_revision, attempts
                """, (rs, row) -> new ClaimedRow(rs.getObject("id", UUID.class),
                rs.getObject("report_id", UUID.class), rs.getObject("case_id", UUID.class),
                rs.getInt("source_revision"), rs.getInt("attempts")),
            leaseSeconds, leaseToken, maxAttempts, limit);
    }

    /**
     * A worker that died on its last attempt leaves a queued row nobody may claim any more.
     * Without this sweep that report would say QUEUED forever instead of admitting manual work.
     */
    public int abandonExhausted(int maxAttempts) {
        return jdbc.update("""
                UPDATE report_analysis
                   SET status = 'MANUAL_REQUIRED', leased_until = NULL, leased_by = NULL, updated_at = now()
                 WHERE status = 'QUEUED' AND attempts >= ?
                   AND (leased_until IS NULL OR leased_until <= now())
                """, maxAttempts);
    }

    /** Zero rows means the lease was lost while Python was answering: another worker owns the row. */
    public int finish(UUID id, UUID leaseToken, String status, UUID suggestionId) {
        return jdbc.update("""
                UPDATE report_analysis
                   SET status = ?, suggestion_id = ?, leased_until = NULL, leased_by = NULL, updated_at = now()
                 WHERE id = ? AND leased_by = ?
                """, status, suggestionId, id, leaseToken);
    }

    public int release(UUID id, int delaySeconds, UUID leaseToken) {
        return jdbc.update("""
                UPDATE report_analysis
                   SET leased_until = NULL, leased_by = NULL, updated_at = now(),
                       available_at = now() + make_interval(secs => ?)
                 WHERE id = ? AND leased_by = ?
                """, delaySeconds, id, leaseToken);
    }

    public void insertSuggestion(UUID id, UUID caseId, UUID reportId, int sourceRevision,
                                 String modelVersion, String payloadJson) {
        jdbc.update("""
                INSERT INTO model_suggestion(id, case_id, report_id, source_revision, model_version, payload)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                """, id, caseId, reportId, sourceRevision, modelVersion, payloadJson);
    }

    public Optional<AnalysisRow> findByReport(UUID reportId) {
        return jdbc.query("""
                SELECT id, status, suggestion_id,
                       (leased_until IS NOT NULL AND leased_until > now()) AS leased
                  FROM report_analysis WHERE report_id = ?
                """, (rs, row) -> new AnalysisRow(rs.getObject("id", UUID.class), rs.getString("status"),
                rs.getObject("suggestion_id", UUID.class), rs.getBoolean("leased")), reportId)
            .stream().findFirst();
    }

    public Optional<SuggestionRow> findSuggestion(UUID id) {
        return jdbc.query("SELECT source_revision, payload::text AS payload FROM model_suggestion WHERE id = ?",
                (rs, row) -> new SuggestionRow(rs.getInt("source_revision"), rs.getString("payload")), id)
            .stream().findFirst();
    }
}
