package ru.koren.notifications.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.koren.cases.application.CaseService;
import ru.koren.max.application.MaxSender;
import ru.koren.notifications.persistence.OutboxRepository;

/** Sends queued notifications, re-checking the recipient's access immediately before each send. */
public class OutboxWorker {

    public static final int MAX_ATTEMPTS = 5;
    static final int LEASE_SECONDS = 60;
    static final int BATCH = 20;
    private static final long[] BACKOFF_SECONDS = {10, 60, 300, 1800};

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);

    public record Drained(int sent, int failed, int cancelled) {}

    private final OutboxRepository outbox;
    private final CaseService cases;
    private final MaxSender sender;
    private final TransactionTemplate tx;
    private final Clock clock;

    public OutboxWorker(OutboxRepository outbox, CaseService cases, MaxSender sender,
                        PlatformTransactionManager transactions, Clock clock) {
        this.outbox = outbox;
        this.cases = cases;
        this.sender = sender;
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    public Drained drainOnce() {
        Timestamp now = Timestamp.from(clock.instant());
        List<OutboxRepository.OutboxRow> claimed =
            tx.execute(status -> outbox.claim(now, LEASE_SECONDS, MAX_ATTEMPTS, BATCH));
        int sent = 0;
        int failed = 0;
        int cancelled = 0;
        for (var row : claimed) {
            // Access is re-checked here, not at enqueue time: it may have been revoked since.
            // A reply with no case carries no case data, so there is no case-level right to check.
            if (row.caseId() != null && !cases.mayRead(row.recipientId(), row.caseId())) {
                outbox.cancel(row.id(), "Recipient may no longer read this case");
                cancelled++;
                continue;
            }
            try {
                // Deliberately outside any transaction: an open transaction must not wait on HTTP.
                sender.send(row.recipientId(), row.eventType(), row.payload());
                outbox.markSent(row.id());
                sent++;
            } catch (RuntimeException failure) {
                // Only the failure class is stored and logged; the payload carries user text.
                log.warn("Outbox event {} failed on attempt {}: {}", row.id(), row.attempts(),
                    failure.getClass().getSimpleName());
                outbox.release(row.id(),
                    Timestamp.from(clock.instant().plusSeconds(backoff(row.attempts()))),
                    failure.getClass().getSimpleName());
                failed++;
            }
        }
        return new Drained(sent, failed, cancelled);
    }

    private static long backoff(int attempts) {
        return BACKOFF_SECONDS[Math.min(Math.max(attempts, 1), BACKOFF_SECONDS.length) - 1];
    }
}
