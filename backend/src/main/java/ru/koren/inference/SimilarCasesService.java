package ru.koren.inference;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static ru.koren.inference.InferenceContract.*;
import static ru.koren.inference.IntakeContract.*;

/** Internal application service. verifiedUserId must come from server authentication, never a request body. */
@Service
@Profile("!no-db")
public class SimilarCasesService {
    private static final int MAX_REQUEST_BYTES = 512 * 1024;
    private final JdbcTemplate jdbc;
    private final InferenceClient inference;
    private final TransactionTemplate read;
    private final JsonMapper json = JsonMapper.builder().build();

    public SimilarCasesService(JdbcTemplate jdbc, PlatformTransactionManager transactions, InferenceClient inference) {
        this.jdbc = jdbc;
        this.inference = inference;
        read = new TransactionTemplate(transactions);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public SimilarResponse find(UUID verifiedUserId, UUID caseId, int limit) {
        Objects.requireNonNull(verifiedUserId);
        Objects.requireNonNull(caseId);
        require(limit >= 1 && limit <= 20, "Invalid similarity limit");
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Run similarity outside the command transaction");
        var request = Objects.requireNonNull(read.execute(status -> prepare(verifiedUserId, caseId, limit)));
        var response = inference.similar(request);
        // Re-read every supplied snapshot, not only matches: a concurrent change can invalidate a negative result too.
        boolean current = Boolean.TRUE.equals(read.execute(status -> {
            if (!request.query().equals(load(verifiedUserId, caseId))) return false;
            for (var candidate : request.candidates()) {
                try {
                    if (!candidate.equals(load(verifiedUserId, candidate.caseId()))) return false;
                } catch (AccessDeniedException denied) {
                    return false;
                }
            }
            return true;
        }));
        return current ? response : InferenceClient.unavailable(request);
    }

    private SimilarRequest prepare(UUID actor, UUID caseId, int limit) {
        var query = load(actor, caseId);
        List<UUID> ids = jdbc.query("""
                SELECT c.id FROM service_case c
                JOIN membership m ON m.house_id = c.house_id AND m.user_id = ? AND m.revoked_at IS NULL
                WHERE c.house_id = ? AND c.id <> ? AND c.submission_status = 'CONFIRMED'
                  AND (c.author_id = ? OR m.role = 'DISPATCHER')
                ORDER BY c.created_at DESC, c.id LIMIT 20
                """, (rs, row) -> rs.getObject("id", UUID.class), actor, query.houseId(), caseId, actor);
        var candidates = new ArrayList<ConfirmedCaseContext>();
        for (UUID id : ids) {
            candidates.add(load(actor, id));
            if (json.writeValueAsBytes(new SimilarRequest(query, candidates, limit)).length > MAX_REQUEST_BYTES) {
                candidates.removeLast();
                break;
            }
        }
        return new SimilarRequest(query, candidates, limit);
    }

    private ConfirmedCaseContext load(UUID actor, UUID id) {
        var headers = jdbc.query("""
                SELECT c.id, c.house_id, c.version, c.source_revision, r.source_text
                FROM service_case c
                JOIN case_revision r ON r.case_id = c.id AND r.revision = c.source_revision
                JOIN membership m ON m.house_id = c.house_id AND m.user_id = ? AND m.revoked_at IS NULL
                WHERE c.id = ? AND c.submission_status = 'CONFIRMED'
                  AND (c.author_id = ? OR m.role = 'DISPATCHER')
                """, (rs, row) -> new Header(rs.getObject("id", UUID.class), rs.getObject("house_id", UUID.class),
                Math.toIntExact(rs.getLong("version")), rs.getInt("source_revision"), rs.getString("source_text")),
                actor, id, actor);
        if (headers.isEmpty()) throw new AccessDeniedException("Case is not available for similarity");
        Header header = headers.getFirst();
        var items = jdbc.query("""
                SELECT i.id, i.content_version, i.work_round, r.category, r.location, r.symptom, r.confirmed_at
                FROM case_item i JOIN item_revision r ON r.item_id = i.id AND r.content_version = i.content_version
                WHERE i.case_id = ? ORDER BY i.id
                """, (rs, row) -> {
                    if (rs.getTimestamp("confirmed_at") == null)
                        throw new IllegalStateException("Unconfirmed item in confirmed case");
                    return new Item(rs.getObject("id", UUID.class), rs.getInt("content_version"), rs.getInt("work_round"),
                            Category.valueOf(rs.getString("category")), rs.getString("location"), rs.getString("symptom"));
                }, id);
        return new ConfirmedCaseContext(header.id(), header.version(), header.revision(), header.house(),
                "CONFIRMED", header.source(), items);
    }

    private record Header(UUID id, UUID house, int version, int revision, String source) {}
}
