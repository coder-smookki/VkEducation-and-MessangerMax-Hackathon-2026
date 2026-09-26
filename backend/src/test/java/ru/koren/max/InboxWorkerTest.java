package ru.koren.max;

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
import ru.koren.max.application.InboxWorker;
import ru.koren.max.application.UpdateHandler;

import static org.junit.jupiter.api.Assertions.*;

/** S-05: the inbox is drained under a lease, a refusal is retried, and nothing is lost after ACK. */
@SpringBootTest(properties = {
    "spring.datasource.url=${DB_TEST_URL}", "spring.datasource.username=${DB_TEST_USER:koren}",
    "spring.datasource.password=${DB_TEST_PASSWORD:koren_local_only}",
    "koren.max.bot-token=k1-test-bot-token:not-a-real-secret",
    "koren.max.webhook-secret=k1-test-webhook-secret:not-a-real-secret"
})
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class InboxWorkerTest {

    static class RecordingHandler implements UpdateHandler {
        final List<String> seen = new CopyOnWriteArrayList<>();
        volatile boolean refuse = false;

        @Override
        public void handle(String eventKey, String payload) {
            seen.add(eventKey);
            if (refuse) throw new IllegalStateException("handler refused");
        }
    }

    @TestConfiguration
    static class Handlers {
        @Bean
        @Primary
        RecordingHandler recordingHandler() {
            return new RecordingHandler();
        }
    }

    @Autowired InboxWorker worker;
    @Autowired RecordingHandler handler;
    @Autowired JdbcTemplate jdbc;

    private String key;

    @BeforeEach
    void reset() {
        handler.seen.clear();
        handler.refuse = false;
        key = "test:" + UUID.randomUUID();
        // Only this event is pending: everything older is parked so the batch is predictable.
        jdbc.update("UPDATE inbox_event SET processed_at = now() WHERE processed_at IS NULL");
        jdbc.update("""
                INSERT INTO inbox_event(id, event_key, payload_hash, payload)
                VALUES (?, ?, repeat('a', 64), ?::jsonb)
                """, UUID.randomUUID(), key, "{\"update_id\":1,\"message\":{\"text\":\"/start\"}}");
    }

    private Integer intOf(String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM inbox_event WHERE event_key = ?",
            Integer.class, key);
    }

    private boolean processed() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT processed_at IS NOT NULL FROM inbox_event WHERE event_key = ?", Boolean.class, key));
    }

    @Test
    void aPendingEventIsHandledOnceAndMarked() {
        var drained = worker.drainOnce();

        assertEquals(1, drained.handled());
        assertEquals(0, drained.failed());
        assertEquals(List.of(key), handler.seen, "the worker must pass the event's own key");
        assertTrue(processed());
        assertEquals(0, worker.drainOnce().handled(), "a processed event is never claimed again");
    }

    @Test
    void aRefusedEventStaysPendingAndWaits() {
        handler.refuse = true;
        var drained = worker.drainOnce();

        assertEquals(0, drained.handled());
        assertEquals(1, drained.failed());
        assertFalse(processed(), "MAX was already acknowledged, so the event must not disappear");
        assertEquals(1, intOf("attempts"));
        assertEquals(0, worker.drainOnce().failed(), "the backoff must hold the event back");
    }

    @Test
    void anExhaustedEventIsNoLongerClaimedButStaysVisible() {
        handler.refuse = true;
        for (int attempt = 0; attempt < 10; attempt++) {
            jdbc.update("UPDATE inbox_event SET available_at = now() - interval '1 hour',"
                + " leased_until = NULL WHERE event_key = ?", key);
            worker.drainOnce();
        }

        assertEquals(InboxWorker.MAX_ATTEMPTS, intOf("attempts"),
            "attempts must stop at the cap instead of growing forever");
        assertFalse(processed(), "an undeliverable update stays queryable, never silently closed");
    }

    @Test
    void aLiveLeaseIsRespectedAndAnExpiredOneIsRetaken() {
        jdbc.update("UPDATE inbox_event SET leased_until = now() + interval '1 hour' WHERE event_key = ?", key);
        assertEquals(0, worker.drainOnce().handled(), "another worker holds this event");

        jdbc.update("UPDATE inbox_event SET leased_until = now() - interval '1 hour' WHERE event_key = ?", key);
        assertEquals(1, worker.drainOnce().handled(), "a dead worker's lease must expire by itself");
    }
}
