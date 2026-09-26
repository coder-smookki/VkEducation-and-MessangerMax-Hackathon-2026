package ru.koren.notifications.application;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.koren.cases.application.CaseService;
import ru.koren.max.application.MaxDeliveryException;
import ru.koren.max.application.MaxSender;
import ru.koren.notifications.persistence.OutboxRepository;

/**
 * Sends queued notifications, re-checking the recipient's access immediately before each send.
 * A refusal stops at once, a rate limit is waited out by the whole batch, anything else is retried
 * with backoff until the last attempt marks the row failed.
 */
public class OutboxWorker {

    public static final int MAX_ATTEMPTS = 5;
    static final int LEASE_SECONDS = 60;
    static final int BATCH = 20;
    private static final int[] BACKOFF_SECONDS = {10, 60, 300, 1800};

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);

    public record Drained(int sent, int failed, int cancelled, int deferred) {}

    private final OutboxRepository outbox;
    private final CaseService cases;
    private final MaxSender sender;
    private final TransactionTemplate tx;

    public OutboxWorker(OutboxRepository outbox, CaseService cases, MaxSender sender,
                        PlatformTransactionManager transactions) {
        this.outbox = outbox;
        this.cases = cases;
        this.sender = sender;
        this.tx = new TransactionTemplate(transactions);
    }

    public Drained drainOnce() {
        // A fresh token per batch, so finishing a row proves we still hold the lease we took.
        UUID leaseToken = UUID.randomUUID();
        List<OutboxRepository.OutboxRow> claimed =
            tx.execute(status -> outbox.claim(LEASE_SECONDS, MAX_ATTEMPTS, BATCH, leaseToken));
        int sent = 0;
        int failed = 0;
        int cancelled = 0;
        int deferred = 0;
        for (int index = 0; index < claimed.size(); index++) {
            var row = claimed.get(index);
            // Access is re-checked here, not at enqueue time: it may have been revoked since.
            // A reply with no case carries no case data, so there is no case-level right to check.
            if (row.caseId() != null && !cases.mayRead(row.recipientId(), row.caseId())) {
                outbox.cancel(row.id(), "Recipient may no longer read this case", leaseToken);
                cancelled++;
                continue;
            }
            try {
                // Deliberately outside any transaction: an open transaction must not wait on HTTP.
                sender.send(row.recipientId(), row.eventType(), row.payload());
                if (outbox.markSent(row.id(), leaseToken) == 0) {
                    // The lease expired mid-send; another worker owns the row and may have sent it too.
                    log.warn("Outbox event {} completed after its lease expired; delivery may be doubled",
                        row.id());
                }
                sent++;
            } catch (MaxDeliveryException refusal) {
                log.warn("Outbox event {} not delivered on attempt {}: {}", row.id(), row.attempts(),
                    refusal.reason());
                failed++;
                switch (refusal.kind()) {
                    case REJECTED -> outbox.fail(row.id(), refusal.reason(), leaseToken);
                    case UNAVAILABLE -> retryOrFail(row, refusal.reason(), backoff(row.attempts()), leaseToken);
                    case RATE_LIMITED -> {
                        int wait = Math.max(backoff(row.attempts()), refusal.retryAfterSeconds());
                        retryOrFail(row, refusal.reason(), wait, leaseToken);
                        // The bot is over its limit, not this message: sending the rest now would only
                        // burn their attempts. They wait just as long and get their attempt back.
                        for (var rest : claimed.subList(index + 1, claimed.size())) {
                            deferred += outbox.defer(rest.id(), wait, leaseToken);
                        }
                        return new Drained(sent, failed, cancelled, deferred);
                    }
                }
            } catch (RuntimeException failure) {
                // Only the failure class is stored and logged; the payload carries user text.
                log.warn("Outbox event {} failed on attempt {}: {}", row.id(), row.attempts(),
                    failure.getClass().getSimpleName());
                retryOrFail(row, failure.getClass().getSimpleName(), backoff(row.attempts()), leaseToken);
                failed++;
            }
        }
        return new Drained(sent, failed, cancelled, deferred);
    }

    /** The claim already counted this attempt: the last one closes the row, earlier ones wait. */
    private void retryOrFail(OutboxRepository.OutboxRow row, String reason, int delaySeconds, UUID leaseToken) {
        if (row.attempts() >= MAX_ATTEMPTS) {
            outbox.fail(row.id(), reason, leaseToken);
        } else {
            outbox.release(row.id(), delaySeconds, reason, leaseToken);
        }
    }

    private static int backoff(int attempts) {
        return BACKOFF_SECONDS[Math.min(Math.max(attempts, 1), BACKOFF_SECONDS.length) - 1];
    }
}
