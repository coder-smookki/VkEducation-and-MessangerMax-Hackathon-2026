package ru.koren.notifications;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.koren.max.application.MaxDeliveryException;
import ru.koren.max.application.MaxSender;
import ru.koren.notifications.application.OutboxWorker;
import ru.koren.notifications.persistence.OutboxRepository;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S-05: delivery is durable, retried with backoff, and re-authorised immediately before each send.
 * S-07 / QA-R05: MAX's answers are told apart - a refusal stops at once, a rate limit is waited out by
 * the whole batch, the last failed attempt is recorded as failed. The sender is a controlled mock.
 * No fixed clock here on purpose: lease and backoff arithmetic must run against real time.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=${DB_TEST_URL}", "spring.datasource.username=${DB_TEST_USER:koren}",
    "spring.datasource.password=${DB_TEST_PASSWORD:koren_local_only}",
    "koren.max.bot-token=k1-test-bot-token:not-a-real-secret"
})
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class OutboxWorkerTest {

    static class RecordingSender implements MaxSender {
        final List<UUID> sent = new CopyOnWriteArrayList<>();
        volatile boolean refuse = false;
        /** What MAX answers instead of accepting, as a real sender would report it. */
        volatile MaxDeliveryException answer = null;

        @Override
        public void send(UUID recipientId, String eventType, String payload) {
            sent.add(recipientId);
            if (answer != null) throw answer;
            if (refuse) throw new IllegalStateException("transport refused");
        }
    }

    @TestConfiguration
    static class Senders {
        @Bean
        @Primary
        RecordingSender recordingSender() {
            return new RecordingSender();
        }
    }

    @Autowired OutboxWorker worker;
    @Autowired OutboxRepository outbox;
    @Autowired RecordingSender sender;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    private UUID author;
    private UUID dispatcher;
    private UUID house;
    private UUID caseId;
    private String key;

    @BeforeEach
    void fixture() {
        sender.sent.clear();
        sender.refuse = false;
        sender.answer = null;
        author = UUID.randomUUID();
        dispatcher = UUID.randomUUID();
        house = UUID.randomUUID();
        caseId = UUID.randomUUID();
        key = "test:" + UUID.randomUUID();
        // Park anything left by earlier tests so drainOnce counts only what this test queued.
        jdbc.update("UPDATE outbox_event SET sent_at = now() WHERE sent_at IS NULL AND failed_at IS NULL");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO app_user(id, max_user_id) VALUES (?, ?), (?, ?)",
                author, Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L),
                dispatcher, Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000_000L));
            jdbc.update("INSERT INTO house(id, address) VALUES (?, ?)", house, "Outbox " + house);
            jdbc.update("INSERT INTO membership VALUES (?,?,'RESIDENT'), (?,?,'DISPATCHER')",
                author, house, dispatcher, house);
            // The two case FKs point at each other, so both rows must land in one transaction.
            jdbc.update("INSERT INTO service_case(id, house_id, author_id, submission_status)"
                + " VALUES (?, ?, ?, 'CONFIRMED')", caseId, house, author);
            jdbc.update("INSERT INTO case_revision(case_id, revision, source_text) VALUES (?, 1, ?)",
                caseId, "Тёмный подъезд и незакрывающаяся дверь.");
        });
    }

    private void queue(UUID recipient) {
        assertTrue(outbox.enqueue(key, caseId, recipient, "CASE_ACCEPTED", "{\"caseId\":\"" + caseId + "\"}"));
    }

    private boolean isSent() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT sent_at IS NOT NULL FROM outbox_event WHERE deduplication_key = ?", Boolean.class, key));
    }

    private String lastError() {
        return jdbc.queryForObject("SELECT last_error FROM outbox_event WHERE deduplication_key = ?",
            String.class, key);
    }

    private boolean isFailed(String dedupKey) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT failed_at IS NOT NULL FROM outbox_event WHERE deduplication_key = ?", Boolean.class, dedupKey));
    }

    private static MaxDeliveryException http(int status, String retryAfter) {
        return MaxDeliveryException.fromHttp(status, retryAfter, Instant.now()).orElseThrow();
    }

    private int attempts() {
        return jdbc.queryForObject("SELECT attempts FROM outbox_event WHERE deduplication_key = ?",
            Integer.class, key);
    }

    @Test
    void aQueuedNotificationIsSentOnceAndMarked() {
        queue(author);
        var drained = worker.drainOnce();
        assertEquals(1, drained.sent());
        assertEquals(List.of(author), sender.sent);
        assertTrue(isSent());
        assertNull(lastError());
        assertEquals(0, worker.drainOnce().sent(), "a sent notification is never claimed again");
    }

    @Test
    void aRefusedSendStaysQueuedAndKeepsItsReason() {
        sender.refuse = true;
        queue(dispatcher);
        var drained = worker.drainOnce();
        assertEquals(0, drained.sent());
        assertEquals(1, drained.failed());
        assertFalse(isSent());
        assertEquals(1, attempts());
        assertEquals("IllegalStateException", lastError(), "the row must explain itself without logs");
        assertEquals(0, worker.drainOnce().failed(), "backoff must hold it back");
    }

    @Test
    void aRecipientWhoLostAccessIsCancelledRatherThanSent() {
        queue(dispatcher);
        jdbc.update("UPDATE membership SET revoked_at = now() WHERE user_id = ? AND house_id = ?",
            dispatcher, house);
        var drained = worker.drainOnce();
        assertEquals(1, drained.cancelled());
        assertEquals(0, drained.sent());
        assertTrue(sender.sent.isEmpty(), "a revoked reader must never reach the transport at all");
        assertFalse(isSent(), "a cancelled notification is not delivery and must not look like it");
        assertEquals("Recipient may no longer read this case", lastError());
    }

    @Test
    void theSameNewsIsNotQueuedTwice() {
        queue(author);
        assertFalse(outbox.enqueue(key, caseId, author, "CASE_ACCEPTED", "{\"caseId\":\"x\"}"));
        assertEquals(1, worker.drainOnce().sent());
        assertEquals(1, sender.sent.size());
    }

    @Test
    void anExhaustedNotificationStopsBeingClaimedAndStaysUnsent() {
        sender.refuse = true;
        queue(author);
        for (int attempt = 0; attempt < 10; attempt++) {
            jdbc.update("UPDATE outbox_event SET available_at = now() - interval '1 hour',"
                + " leased_until = NULL WHERE deduplication_key = ?", key);
            worker.drainOnce();
        }
        assertEquals(OutboxWorker.MAX_ATTEMPTS, attempts());
        assertFalse(isSent(), "an undeliverable notification stays queryable, never quietly closed");
        assertTrue(isFailed(key), "the last attempt says in the row that nothing more will be tried");
        assertEquals("IllegalStateException", lastError());
    }

    @Test
    void aLiveLeaseIsRespectedAndAnExpiredOneIsRetaken() {
        queue(author);
        jdbc.update("UPDATE outbox_event SET leased_until = now() + interval '1 hour'"
            + " WHERE deduplication_key = ?", key);
        assertEquals(0, worker.drainOnce().sent(), "another worker holds this notification");
        jdbc.update("UPDATE outbox_event SET leased_until = now() - interval '1 hour'"
            + " WHERE deduplication_key = ?", key);
        assertEquals(1, worker.drainOnce().sent(), "a dead worker's lease must expire by itself");
    }
    @Test
    void aWorkerThatLostItsLeaseCannotRecordTheDelivery() {
        queue(author);
        UUID myToken = UUID.randomUUID();
        var claimed = outbox.claim(60, OutboxWorker.MAX_ATTEMPTS, 10, myToken);
        assertEquals(1, claimed.size());
        // Our lease expires and another worker takes the row while our send is still in flight.
        jdbc.update("UPDATE outbox_event SET leased_by = ?, leased_until = now() + interval '1 hour'"
            + " WHERE deduplication_key = ?", UUID.randomUUID(), key);
        assertEquals(0, outbox.markSent(claimed.getFirst().id(), myToken),
            "a lost lease must not let us record a clean delivery");
        assertFalse(isSent(), "the row belongs to the other worker now");
    }

    @Test
    void aPermanentRefusalFailsAtOnceWithoutRetrying() {
        sender.answer = http(403, null);
        queue(author);
        var drained = worker.drainOnce();
        assertEquals(1, drained.failed());
        assertTrue(isFailed(key), "a blocked bot will not unblock itself by being asked again");
        assertEquals("REJECTED_403", lastError());
        assertEquals(1, attempts());
        jdbc.update("UPDATE outbox_event SET available_at = now() - interval '1 hour' WHERE deduplication_key = ?", key);
        worker.drainOnce();
        assertEquals(1, sender.sent.size(), "a failed notification is never claimed again");
    }

    @Test
    void anUnavailableMaxIsRetriedLaterAndNotFailed() {
        sender.answer = http(503, null);
        queue(author);
        assertEquals(1, worker.drainOnce().failed());
        assertFalse(isFailed(key), "a server error is temporary; the row waits for another attempt");
        assertEquals("UNAVAILABLE_503", lastError());
        assertEquals(0, worker.drainOnce().failed(), "backoff holds it back");
    }

    @Test
    void aRateLimitIsWaitedOutAndHoldsBackTheRestOfTheBatch() {
        sender.answer = http(429, "120");
        List<String> keys = List.of(key, "test:" + UUID.randomUUID(), "test:" + UUID.randomUUID());
        for (String each : keys) {
            assertTrue(outbox.enqueue(each, caseId, author, "CASE_ACCEPTED", "{\"caseId\":\"" + caseId + "\"}"));
        }
        var drained = worker.drainOnce();

        assertEquals(1, sender.sent.size(), "after a 429 nothing else is sent into the same limit");
        assertEquals(1, drained.failed());
        assertEquals(2, drained.deferred());
        for (String each : keys) {
            assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT available_at > now() + interval '110 seconds'"
                    + " AND failed_at IS NULL FROM outbox_event WHERE deduplication_key = ?", Boolean.class, each),
                "every row waits at least as long as MAX asked");
        }
        assertEquals(List.of(0, 0, 1), jdbc.queryForList("SELECT attempts FROM outbox_event"
                + " WHERE deduplication_key IN (?, ?, ?) ORDER BY attempts", Integer.class,
            keys.get(0), keys.get(1), keys.get(2)), "only the message that met the limit spent an attempt");
    }
}
