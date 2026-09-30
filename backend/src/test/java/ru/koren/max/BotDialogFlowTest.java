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
 * S-05: the full bot dialog over the real stack - a case is created and confirmed from chat, and an
 * observation is answered for the exact round the bot asked about. Nothing is sent to MAX here.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=${DB_TEST_URL}", "spring.datasource.username=${DB_TEST_USER:koren}",
    "spring.datasource.password=${DB_TEST_PASSWORD:koren_local_only}",
    "koren.max.bot-token=k1-test-bot-token:not-a-real-secret",
    "koren.max.webhook-secret=k1-test-webhook-secret:not-a-real-secret"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class BotDialogFlowTest {

    private static final String SECRET = "k1-test-webhook-secret:not-a-real-secret";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired InboxWorker worker;

    private long maxUserId;
    private long updateId;

    @BeforeEach
    void freshSender() {
        maxUserId = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L);
        updateId = Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000_000L);
        jdbc.update("UPDATE inbox_event SET processed_at = now() WHERE processed_at IS NULL");
    }

    /** One message from the person, delivered and drained, exactly as the worker would do it. */
    private void send(String text) throws Exception {
        updateId++;
        mvc.perform(post("/max/webhook")
                .header(MaxWebhookController.SECRET_HEADER, SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"update_id\":" + updateId + ",\"message\":{\"sender\":{\"user_id\":"
                    + maxUserId + "},\"body\":{\"text\":\"" + text + "\"}}}"))
            .andExpect(status().isOk());
        assertEquals(1, worker.drainOnce().handled(), "the worker should have handled: " + text);
    }

    private UUID actor() {
        return jdbc.queryForObject("SELECT id FROM app_user WHERE max_user_id = ?", UUID.class, maxUserId);
    }

    private List<String> replies() {
        return jdbc.queryForList("""
                SELECT payload->>'text' FROM outbox_event
                 WHERE event_type = 'BOT_REPLY' AND recipient_id = ?
                """, String.class, actor());
    }

    private void assertReplied(String fragment) {
        assertTrue(replies().stream().anyMatch(reply -> reply.contains(fragment)),
            "no reply contained \"" + fragment + "\"; got " + replies());
    }

    private void assertNeverReplied(String fragment) {
        assertTrue(replies().stream().noneMatch(reply -> reply.contains(fragment)),
            "a reply contained \"" + fragment + "\" and should not; got " + replies());
    }

    private String state() {
        return jdbc.queryForObject("SELECT state FROM bot_dialog WHERE user_id = ?", String.class, actor());
    }

    private void giveHouse() {
        UUID house = UUID.randomUUID();
        jdbc.update("INSERT INTO house(id, address) VALUES (?, ?)", house, "Bot flow " + house);
        jdbc.update("INSERT INTO membership VALUES (?,?,'RESIDENT')", actor(), house);
    }

    private UUID caseOfActor() {
        return jdbc.queryForObject("SELECT id FROM service_case WHERE author_id = ?", UUID.class, actor());
    }

    @Test
    void aPersonWithoutAHouseIsToldToAskForAnInvite() throws Exception {
        send("/start");
        send("новое обращение");
        send("В подъезде темно");

        assertReplied("нет доступа ни к одному дому");
        assertEquals("IDLE", state(), "a dead end must not leave the dialog waiting");
    }

    @Test
    void aCaseIsCreatedItemByItemAndConfirmedFromChat() throws Exception {
        send("/start");
        giveHouse();
        send("новое обращение");
        assertEquals("AWAITING_CASE_TEXT", state());

        send("На третьем этаже темно, и входная дверь не закрывается");
        assertEquals("AWAITING_ITEMS", state());
        assertReplied("Опишите один пункт");

        send("свет; 3 этаж; не горит лампа");
        send("дверь; вход; не закрывается");
        send("готово");

        assertReplied("Обращение подтверждено, пунктов: 2");
        assertEquals("IDLE", state());
        assertEquals("CONFIRMED", jdbc.queryForObject(
            "SELECT submission_status FROM service_case WHERE author_id = ?", String.class, actor()));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM case_item WHERE case_id = ?",
            Integer.class, caseOfActor()));
    }

    @Test
    void aMalformedItemIsRefusedWithoutTouchingTheDraft() throws Exception {
        send("/start");
        giveHouse();
        send("новое обращение");
        send("Темно в подъезде");
        send("просто текст без точек с запятой");
        send("лифт; вход; не работает");

        assertReplied("Не разобрал");
        assertReplied("Такой категории нет");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM case_item WHERE case_id = ?",
            Integer.class, caseOfActor()), "neither message may add an item");
        assertEquals("AWAITING_ITEMS", state(), "the dialog keeps waiting instead of giving up");
    }

    @Test
    void confirmingAnEmptyListIsRefused() throws Exception {
        send("/start");
        giveHouse();
        send("новое обращение");
        send("Темно");
        send("готово");

        assertReplied("Пока ни одного пункта");
        assertEquals("DRAFT", jdbc.queryForObject(
            "SELECT submission_status FROM service_case WHERE author_id = ?", String.class, actor()));
    }

    /** Reaches REPORTED_DONE the short way: the report path itself is covered by ReportHttpTest. */
    private void reportWorkDone() {
        jdbc.update("""
                UPDATE case_item SET work_status = 'REPORTED_DONE', work_round = 1,
                       observation = 'NOT_RECEIVED', version = version + 1
                 WHERE case_id = ?
                """, caseOfActor());
    }

    @Test
    void theBotAsksAboutEachFinishedItemAndRecordsTheAnswer() throws Exception {
        send("/start");
        giveHouse();
        send("новое обращение");
        send("Темно и дверь не закрывается");
        send("свет; 3 этаж; не горит лампа");
        send("дверь; вход; не закрывается");
        send("готово");
        reportWorkDone();

        send("наблюдение");
        assertEquals("AWAITING_OBSERVATION", state());
        assertReplied("не горит лампа");

        send("восстановлено");
        assertReplied("Записал ваш ответ");
        assertReplied("не закрывается");
        assertEquals("AWAITING_OBSERVATION", state(), "the next finished item is asked about at once");

        send("не помогло");
        assertReplied("Больше вопросов по работам нет");
        assertEquals("IDLE", state());

        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM resident_observation"
            + " WHERE case_id = ? AND value = 'RESTORED'", Integer.class, caseOfActor()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM resident_observation"
            + " WHERE case_id = ? AND value = 'PERSISTS'", Integer.class, caseOfActor()));
    }

    @Test
    void anAnswerAboutAnOldRoundIsRefusedInsteadOfApplied() throws Exception {
        send("/start");
        giveHouse();
        send("новое обращение");
        send("Темно");
        send("свет; 3 этаж; не горит лампа");
        send("готово");
        reportWorkDone();
        send("наблюдение");
        assertEquals("AWAITING_OBSERVATION", state());

        // New work is reported while the question is still open: the answer is about a past round.
        jdbc.update("UPDATE case_item SET work_round = work_round + 1, observation = 'NOT_RECEIVED',"
            + " version = version + 1 WHERE case_id = ?", caseOfActor());
        send("восстановлено");

        assertReplied("появились новые сведения");
        assertNeverReplied("Записал ваш ответ");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM resident_observation WHERE case_id = ?",
            Integer.class, caseOfActor()), "a stale answer must leave no observation at all");
        assertEquals("IDLE", state());
    }

    @Test
    void anUnrecognisedAnswerKeepsTheQuestionOpen() throws Exception {
        send("/start");
        giveHouse();
        send("новое обращение");
        send("Темно");
        send("свет; 3 этаж; не горит лампа");
        send("готово");
        reportWorkDone();
        send("наблюдение");

        send("вроде получше стало");
        assertReplied("Ответьте, пожалуйста, одним из");
        assertEquals("AWAITING_OBSERVATION", state());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM resident_observation WHERE case_id = ?",
            Integer.class, caseOfActor()));
    }
}
