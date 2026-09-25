package ru.koren.max.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.koren.max.persistence.InboxRepository;

/** Drains the inbox under a lease. Never asks MAX again: the update is already ours. */
public class InboxWorker {

    static final int LEASE_SECONDS = 60;
    public static final int MAX_ATTEMPTS = 5;
    static final int BATCH = 20;
    private static final long[] BACKOFF_SECONDS = {5, 30, 120, 600};

    private static final Logger log = LoggerFactory.getLogger(InboxWorker.class);

    public record Drained(int handled, int failed) {}

    private final InboxRepository inbox;
    private final UpdateHandler handler;
    private final TransactionTemplate tx;
    private final Clock clock;

    public InboxWorker(InboxRepository inbox, UpdateHandler handler,
                       PlatformTransactionManager transactions, Clock clock) {
        this.inbox = inbox;
        this.handler = handler;
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    public Drained drainOnce() {
        Timestamp now = Timestamp.from(clock.instant());
        List<InboxRepository.InboxRow> claimed =
            tx.execute(status -> inbox.claim(now, LEASE_SECONDS, MAX_ATTEMPTS, BATCH));
        int handled = 0;
        int failed = 0;
        for (var row : claimed) {
            try {
                // Handling and marking share one transaction: a crash between them would repeat the work.
                tx.executeWithoutResult(status -> {
                    handler.handle(row.payload());
                    inbox.markProcessed(row.id());
                });
                handled++;
            } catch (RuntimeException failure) {
                // Never log the payload: an update carries user text and identifiers.
                log.warn("Inbox event {} failed on attempt {}: {}", row.id(), row.attempts(),
                    failure.getClass().getSimpleName());
                inbox.release(row.id(), Timestamp.from(clock.instant().plusSeconds(backoff(row.attempts()))));
                failed++;
            }
        }
        return new Drained(handled, failed);
    }

    private static long backoff(int attempts) {
        return BACKOFF_SECONDS[Math.min(Math.max(attempts, 1), BACKOFF_SECONDS.length) - 1];
    }
}
