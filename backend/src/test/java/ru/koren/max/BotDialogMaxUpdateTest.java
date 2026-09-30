package ru.koren.max;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import ru.koren.max.application.InboxWorker;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The envelopes MAX's Update docs describe, as MAX would send them: no update_id, the secret in
 * X-Max-Bot-Api-Secret, message_created with message.sender and message.body, bot_started with user.
 * The password comes only from DB_TEST_PASSWORD, without a default in the source.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=${DB_TEST_URL}", "spring.datasource.username=${DB_TEST_USER:koren}",
    "spring.datasource.password=${DB_TEST_PASSWORD}",
    "koren.max.bot-token=k1-test-bot-token:not-a-real-secret",
    "koren.max.webhook-secret=k1-test-webhook-secret:not-a-real-secret"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class BotDialogMaxUpdateTest {

    private static final String SECRET = "k1-test-webhook-secret:not-a-real-secret";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired InboxWorker worker;

    private long maxUserId;
    private long timestamp;

    @BeforeEach
    void freshSender() {
        maxUserId = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L);
        timestamp = System.currentTimeMillis() + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000L);
        // Park anything pending so drainOnce works only on this test's update.
        jdbc.update("UPDATE inbox_event SET processed_at = now() WHERE processed_at IS NULL");
    }

    private void deliver(String update) throws Exception {
        mvc.perform(post("/max/webhook")
                .header("X-Max-Bot-Api-Secret", SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content(update))
            .andExpect(status().isOk());
    }

    private String messageCreated(String text) {
        return "{\"update_type\":\"message_created\",\"timestamp\":" + timestamp
            + ",\"message\":{\"sender\":{\"user_id\":" + maxUserId + ",\"name\":\"Жилец\"},"
            + "\"recipient\":{\"chat_id\":1,\"chat_type\":\"dialog\"},"
            + "\"timestamp\":" + timestamp + ",\"body\":{\"mid\":\"mid." + timestamp + "\",\"seq\":1,"
            + "\"text\":\"" + text + "\"}},\"user_locale\":\"ru\"}";
    }

    private String userEvent(String type) {
        return "{\"update_type\":\"" + type + "\",\"timestamp\":" + timestamp + ",\"chat_id\":1,"
            + "\"user\":{\"user_id\":" + maxUserId + ",\"name\":\"Жилец\"},\"user_locale\":\"ru\"}";
    }

    private List<String> replies() {
        return jdbc.queryForList("""
                SELECT o.payload->>'text' FROM outbox_event o JOIN app_user u ON u.id = o.recipient_id
                 WHERE o.event_type = 'BOT_REPLY' AND u.max_user_id = ? ORDER BY o.created_at
                """, String.class, maxUserId);
    }

    @Test
    void aMessageInMaxsOwnEnvelopeIsAnsweredOnceEvenIfMaxDeliversItTwice() throws Exception {
        deliver(messageCreated("/start"));
        // MAX retries a webhook it thinks failed; with no update_id the content hash is the key.
        deliver(messageCreated("/start"));

        assertEquals(1, worker.drainOnce().handled());
        assertEquals(1, replies().size());
        assertTrue(replies().getFirst().contains("КОРЕНЬ"), replies().getFirst());
    }

    @Test
    void pressingStartInMaxGreetsLikeSlashStart() throws Exception {
        deliver(userEvent("bot_started"));

        assertEquals(1, worker.drainOnce().handled());
        assertEquals(1, replies().size());
        assertTrue(replies().getFirst().contains("КОРЕНЬ"), replies().getFirst());
    }

    @Test
    void anEventTheBotDidNotAskForIsSettledWithoutAnAnswer() throws Exception {
        deliver(userEvent("bot_stopped"));

        assertEquals(1, worker.drainOnce().handled());
        assertTrue(replies().isEmpty());
        assertEquals(0, jdbc.queryForObject(
            "SELECT count(*) FROM inbox_event WHERE processed_at IS NULL", Integer.class));
    }
}
