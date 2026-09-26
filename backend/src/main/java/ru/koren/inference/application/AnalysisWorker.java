package ru.koren.inference.application;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.koren.cases.persistence.CaseRepository;
import ru.koren.inference.InferenceClient;
import ru.koren.inference.InferenceContract.Category;
import ru.koren.inference.InferenceContract.Item;
import ru.koren.inference.InferenceContract.Mode;
import ru.koren.inference.InferenceContract.Request;
import ru.koren.inference.InferenceContract.Response;
import ru.koren.inference.persistence.AnalysisRepository;
import ru.koren.reports.persistence.ReportRepository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs queued report analyses. Python is called outside any transaction; the answer is checked by
 * SuggestionValidator inside the client. A well-formed STUB answer says nothing, so it is stored for
 * the record but never reported as READY.
 */
public class AnalysisWorker {

    public static final int MAX_ATTEMPTS = 3;
    static final int LEASE_SECONDS = 30;
    static final int BATCH = 10;
    private static final int[] BACKOFF_SECONDS = {15, 60};

    private static final Logger log = LoggerFactory.getLogger(AnalysisWorker.class);

    public record Drained(int ready, int manual, int retried) {}

    private enum Outcome { READY, MANUAL, RETRIED, LOST }

    private final AnalysisRepository analyses;
    private final ReportRepository reports;
    private final CaseRepository cases;
    private final InferenceClient inference;
    private final TransactionTemplate tx;
    private final JsonMapper json = JsonMapper.builder().build();

    public AnalysisWorker(AnalysisRepository analyses, ReportRepository reports, CaseRepository cases,
                          InferenceClient inference, PlatformTransactionManager transactions) {
        this.analyses = analyses;
        this.reports = reports;
        this.cases = cases;
        this.inference = inference;
        this.tx = new TransactionTemplate(transactions);
    }

    public Drained drainOnce() {
        UUID leaseToken = UUID.randomUUID();
        List<AnalysisRepository.ClaimedRow> claimed = tx.execute(status -> {
            analyses.abandonExhausted(MAX_ATTEMPTS);
            return analyses.claim(LEASE_SECONDS, MAX_ATTEMPTS, BATCH, leaseToken);
        });
        int ready = 0;
        int manual = 0;
        int retried = 0;
        for (var row : claimed) {
            Outcome outcome;
            try {
                outcome = process(row, leaseToken);
            } catch (RuntimeException failure) {
                // One broken row must not abort the batch: every row claimed with it has already spent
                // an attempt, and would lose it for nothing. Only the class is logged, never the text.
                log.warn("Analysis {} failed on attempt {}: {}", row.id(), row.attempts(),
                    failure.getClass().getSimpleName());
                outcome = retryOrGiveUp(row, leaseToken);
            }
            switch (outcome) {
                case READY -> ready++;
                case MANUAL -> manual++;
                case RETRIED -> retried++;
                case LOST -> { }
            }
        }
        return new Drained(ready, manual, retried);
    }

    private Outcome process(AnalysisRepository.ClaimedRow row, UUID leaseToken) {
        Request request = tx.execute(status -> snapshot(row));
        if (request == null) {
            analyses.finish(row.id(), leaseToken, "MANUAL_REQUIRED", null);
            return Outcome.MANUAL;
        }
        // Deliberately outside any transaction: an open transaction must not wait on Python.
        Optional<Response> answer = inference.tryCompare(request);
        if (answer.isEmpty()) return retryOrGiveUp(row, leaseToken);
        Response response = answer.get();
        boolean substantive = response.mode() == Mode.RULES || response.mode() == Mode.NLI;
        boolean kept = Boolean.TRUE.equals(tx.execute(status -> {
            UUID suggestionId = UUID.randomUUID();
            analyses.insertSuggestion(suggestionId, row.caseId(), row.reportId(), row.sourceRevision(),
                response.modelVersion(), json.writeValueAsString(response));
            if (analyses.finish(row.id(), leaseToken, substantive ? "READY" : "MANUAL_REQUIRED",
                suggestionId) == 1) {
                return true;
            }
            // The lease expired while Python answered: leave no orphaned result behind.
            status.setRollbackOnly();
            return false;
        }));
        if (!kept) {
            log.warn("Analysis {} finished after its lease expired; another worker owns it", row.id());
            return Outcome.LOST;
        }
        return substantive ? Outcome.READY : Outcome.MANUAL;
    }

    /** The claim already counted this attempt: the last one admits manual work, earlier ones wait. */
    private Outcome retryOrGiveUp(AnalysisRepository.ClaimedRow row, UUID leaseToken) {
        if (row.attempts() >= MAX_ATTEMPTS) {
            analyses.finish(row.id(), leaseToken, "MANUAL_REQUIRED", null);
            return Outcome.MANUAL;
        }
        analyses.release(row.id(), backoff(row.attempts()), leaseToken);
        return Outcome.RETRIED;
    }

    /**
     * The full target set of the report, with the content and round each item has right now.
     * Every target is sent, not only those the text seems to mention: absence is not UNRELATED.
     */
    private Request snapshot(AnalysisRepository.ClaimedRow row) {
        var report = reports.findReport(row.reportId()).orElse(null);
        if (report == null) return null;
        var targets = new HashSet<>(reports.findTargets(row.reportId()));
        List<Item> items = cases.findItems(row.caseId()).stream()
            .filter(item -> targets.contains(item.id()))
            .map(item -> new Item(item.id(), item.contentVersion(), item.workRound(),
                Category.valueOf(item.category()), item.location(), item.symptom()))
            .toList();
        if (items.isEmpty()) return null;
        return new Request(row.caseId(), row.sourceRevision(), row.reportId(), report.reportText(), items);
    }

    private static int backoff(int attempts) {
        return BACKOFF_SECONDS[Math.min(Math.max(attempts, 1), BACKOFF_SECONDS.length) - 1];
    }
}
