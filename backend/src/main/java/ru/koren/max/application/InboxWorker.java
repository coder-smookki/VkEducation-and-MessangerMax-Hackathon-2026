package ru.koren.max.application;

import java.util.List;
import java.util.UUID;
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
    private static final int[] BACKOFF_SECONDS = {5, 30, 120, 600};

    private static final Logger log = LoggerFactory.getLogger(InboxWorker.class);

    public record Drained(int handled, int failed) {}

    private final InboxRepository inbox;
    private final UpdateHandler handler;
    private final TransactionTemplate tx;

    public InboxWorker(InboxRepository inbox, UpdateHandler handler, PlatformTransactionManager transactions) {
        this.inbox = inbox;
        this.handler = handler;
        this.tx = new TransactionTemplate(transactions);
    }

    public Drained drainOnce() {
        // A fresh token per batch: even this same worker must not finish a row it re-claimed later.
        UUID leaseToken = UUID.randomUUID();
        List<InboxRepository.InboxRow> claimed =
            tx.execute(status -> inbox.claim(LEASE_SECONDS, MAX_ATTEMPTS, BATCH, leaseToken));
        int handled = 0;
        int failed = 0;
        for (var row : claimed) {
            try {
                // Handling and marking share one transaction: a crash between them would repeat the work.
                boolean kept = Boolean.TRUE.equals(tx.execute(status -> {
                    handler.handle(row.eventKey(), row.payload());
                    return inbox.markProcessed(row.id(), leaseToken) == 1;
                }));
                if (!kept) {
                    // Our lease expired mid-handling and another worker owns the event now.
                    log.warn("Inbox event {} finished after its lease expired; another worker owns it",
                        row.id());
                }
                handled++;
            } catch (RuntimeException failure) {
                // Never log the payload: an update carries user text and identifiers.
                log.warn("Inbox event {} failed on attempt {}: {}", row.id(), row.attempts(),
                    failure.getClass().getSimpleName());
                inbox.release(row.id(), backoff(row.attempts()), leaseToken);
                failed++;
            }
        }
        return new Drained(handled, failed);
    }

    private static int backoff(int attempts) {
        return BACKOFF_SECONDS[Math.min(Math.max(attempts, 1), BACKOFF_SECONDS.length) - 1];
    }
}
