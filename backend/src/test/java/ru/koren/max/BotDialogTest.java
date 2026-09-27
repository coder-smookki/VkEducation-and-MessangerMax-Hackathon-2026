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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.koren.max.application.InboxWorker;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S-05 end to end without MAX: webhook stores the update, the worker drains it, the bot's answer
 * lands in the outbox. Nothing is sent here - a stub transport cannot prove delivery.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=${DB_TEST_URL}", "spring.datasource.username=${DB_TEST_USER:koren}",
    "spring.datasource.password=${DB_TEST_PASSWORD:koren_local_only}",
    "koren.max.bot-token=k1-test-bot-token:not-a-real-secret",
    "koren.max.webhook-secret=k1-test-webhook-secret:not-a-real-secret"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class BotDialogTest {

    private static final String SECRET = "k1-test-webhook-secret:not-a-real-secret";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired InboxWorker worker;
    @Autowired PlatformTransactionManager transactions;

    private long maxUserId;
    private long updateId;

    @BeforeEach
    void freshSender() {
        maxUserId = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L);
        updateId = Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000_000L);
        // Park anything pending so drainOnce works only on this test's update.
        jdbc.update("UPDATE inbox_event SET processed_at = now() WHERE processed_at IS NULL");
    }

    private void deliver(String text) throws Exception {
        mvc.perform(post("/max/webhook")
                .header(MaxWebhookController.SECRET_HEADER, SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"update_id\":" + updateId + ",\"message\":{\"sender\":{\"user_id\":"
                    + maxUserId + "},\"body\":{\"text\":\"" + text + "\"}}}"))
            .andExpect(status().isOk());
    }

    private UUID actor() {
        return jdbc.queryForObject("SELECT id FROM app_user WHERE max_user_id = ?", UUID.class, maxUserId);
    }

    private List<String> replies() {
        return jdbc.queryForList("""
                SELECT payload->>'text' FROM outbox_event
                 WHERE event_type = 'BOT_REPLY' AND recipient_id = ? ORDER BY created_at
                """, String.class, actor());
    }

    @Test
    void startIntroducesTheBotAndOpensADialogRow() throws Exception {
        deliver("/start");
        assertEquals(1, worker.drainOnce().handled());
        assertEquals(1, replies().size());
        assertTrue(replies().getFirst().contains("КОРЕНЬ"), replies().getFirst());
        assertTrue(replies().getFirst().contains("112"), "the greeting says it is not an emergency service");
        assertEquals(Boolean.TRUE, jdbc.queryForObject("""
                SELECT (payload->>'openApp')::boolean FROM outbox_event
                 WHERE event_type = 'BOT_REPLY' AND recipient_id = ?
                """, Boolean.class, actor()), "the greeting offers the app");
        assertEquals("IDLE", jdbc.queryForObject(
            "SELECT state FROM bot_dialog WHERE user_id = ?", String.class, actor()));
    }

    @Test
    void anUnknownSenderIsNeverAnsweredAndStaysInTheInbox() throws Exception {
        mvc.perform(post("/max/webhook")
                .header(MaxWebhookController.SECRET_HEADER, SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"update_id\":" + updateId + ",\"message\":{\"body\":{\"text\":\"привет\"}}}"))
            .andExpect(status().isOk());
        assertEquals(1, worker.drainOnce().failed(), "an update we cannot read must not be guessed at");
        assertFalse(Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT processed_at IS NOT NULL FROM inbox_event WHERE event_key = ?",
            Boolean.class, "update_id:" + updateId)), "it stays visible instead of being dropped");
        assertEquals(0, jdbc.queryForObject(
            "SELECT count(*) FROM outbox_event WHERE deduplication_key = ?",
            Integer.class, "update_id:" + updateId + ":reply"), "no answer was queued for this update");
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM app_user WHERE max_user_id = ?", Integer.class, maxUserId),
            "an unreadable update must not even create a user");
    }

    @Test
    void aRedeliveredUpdateProducesOneReplyOnly() throws Exception {
        deliver("/start");
        deliver("/start");
        deliver("/start");
        assertEquals(1, worker.drainOnce().handled(), "the inbox deduplicated the redelivery");
        assertEquals(1, replies().size(), "and the reply key made the answer idempotent too");
    }

    @Test
    void aStrangerWithoutCasesIsToldSoWithoutBeingGivenAnyCaseData() throws Exception {
        deliver("мои обращения");
        assertEquals(1, worker.drainOnce().handled());
        String reply = replies().getFirst();
        assertTrue(reply.contains("нет обращений"), reply);
    }

    @Test
    void anAuthorIsToldHowManyCasesButNeverTheirContents() throws Exception {
        deliver("/start");
        worker.drainOnce();
        UUID actor = actor();
        UUID house = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO house(id, address) VALUES (?, ?)", house, "Bot " + house);
            jdbc.update("INSERT INTO membership VALUES (?,?,'RESIDENT')", actor, house);
            jdbc.update("INSERT INTO service_case(id, house_id, author_id, submission_status)"
                + " VALUES (?, ?, ?, 'CONFIRMED')", caseId, house, actor);
            jdbc.update("INSERT INTO case_revision(case_id, revision, source_text) VALUES (?, 1, ?)",
                caseId, "Совершенно секретный текст обращения");
        });
        updateId++;
        deliver("Мои обращения");
        assertEquals(1, worker.drainOnce().handled());
        String reply = replies().getLast();
        assertTrue(reply.contains("обращений: 1"), reply);
        assertFalse(reply.contains("секретный"), "case text must never travel to MAX");
    }

    @Test
    void anythingElseGetsAnHonestAnswerRatherThanSilence() throws Exception {
        deliver("починте пожалуйста лифт");
        assertEquals(1, worker.drainOnce().handled());
        assertTrue(replies().getFirst().contains("Пока я понимаю"), replies().getFirst());
    }
}
