package ru.koren.cases;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S-03 over HTTP: acceptance of a confirmed case and the next action of one item.
 * Author A is a resident; the second signed MAX user is the dispatcher of the same house.
 * The clock is fixed at 2027-01-15T08:00:00Z, so a due date is in the past or in the future on purpose.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=${DB_TEST_URL}", "spring.datasource.username=${DB_TEST_USER:koren}",
    "spring.datasource.password=${DB_TEST_PASSWORD:koren_local_only}",
    "koren.max.bot-token=k1-test-bot-token:not-a-real-secret"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class AcceptanceHttpTest {

    private static final String AUTHOR_INIT_DATA =
        "user=%7B%22id%22%3A424242%2C%22first_name%22%3A%22Max%22%2C%22last_name%22%3A%22User%22"
            + "%2C%22username%22%3Anull%2C%22language_code%22%3A%22ru%22%7D&ip=192.168.0.1"
            + "&auth_date=1800000000&query_id=AAE1YmYAAAAANTmv4t3W0h01"
            + "&hash=fa2ff9a4255c0e16b0e5be5a4bc6bded45aadad37b589cebb251fc95d7080ad4";
    private static final String DISPATCHER_INIT_DATA =
        "start_param=ref%3Da%3Db&auth_date=1800000000"
            + "&user=%7B%22id%22%3A31337%2C%22first_name%22%3A%22Eq%22%2C%22last_name%22%3Anull%7D"
            + "&hash=b27c3f005d80d8ccfcca2d27e0dc2d5c33f0be1ee3526adf0945cc854d11cc98";

    private static final String FUTURE_DUE = "2027-01-20T12:00:00Z";
    private static final String PAST_DUE = "2026-09-01T12:00:00Z";

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    static class MutableClock extends Clock {
        private Instant now = Instant.ofEpochSecond(1_800_000_000L);
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }

    @Autowired MutableClock clock;
    @AfterEach
    void resetClock() { clock.now = Instant.ofEpochSecond(1_800_000_000L); }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private final JsonMapper json = JsonMapper.builder().build();

    private record Fixture(MockHttpSession authorSession, MockHttpSession dispatcherSession,
                           UUID author, UUID dispatcher, UUID house, UUID caseId) {}

    private UUID login(MockHttpSession session, String initData) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/max").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"initData\":\"" + initData + "\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("userId").asString());
    }

    /** A fresh house with a resident author and a dispatcher, plus one draft case. Case version is 1. */
    private Fixture newDraft(String address) throws Exception {
        MockHttpSession authorSession = new MockHttpSession();
        UUID author = login(authorSession, AUTHOR_INIT_DATA);
        MockHttpSession dispatcherSession = new MockHttpSession();
        UUID dispatcher = login(dispatcherSession, DISPATCHER_INIT_DATA);
        UUID house = UUID.randomUUID();
        jdbc.update("INSERT INTO house(id, address) VALUES (?, ?)", house, address);
        jdbc.update("INSERT INTO membership VALUES (?,?,'RESIDENT'), (?,?,'DISPATCHER')",
            author, house, dispatcher, house);
        String created = mvc.perform(post("/api/v1/cases").session(authorSession).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"houseId\":\"" + house + "\",\"sourceText\":\"На третьем этаже темно\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return new Fixture(authorSession, dispatcherSession, author, dispatcher, house,
            UUID.fromString(json.readTree(created).get("caseId").asString()));
    }

    /** One item, then confirmation. The case version is 3 afterwards. */
    private String confirmWithOneItem(Fixture f) throws Exception {
        mvc.perform(patch("/api/v1/cases/" + f.caseId() + "/items").session(f.authorSession()).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedCaseVersion\":1,\"items\":[{\"itemId\":null,\"category\":\"LIGHTING\","
                    + "\"location\":\"Этаж 3\",\"symptom\":\"Не горит свет\"}]}"))
            .andExpect(status().isOk());
        return mvc.perform(post("/api/v1/cases/" + f.caseId() + "/confirm").session(f.authorSession()).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedCaseVersion\":2,\"sourceRevision\":1}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    private ResultActions acceptance(Fixture f, MockHttpSession session, String key, String body) throws Exception {
        return mvc.perform(post("/api/v1/cases/" + f.caseId() + "/acceptance").session(session).with(csrf())
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions nextAction(MockHttpSession session, UUID itemId, String key, String body) throws Exception {
        return mvc.perform(put("/api/v1/items/" + itemId + "/next-action").session(session).with(csrf())
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String plan(UUID owner, long caseVersion, long itemVersion, String text, String due) {
        return "{\"expectedCaseVersion\":" + caseVersion + ",\"expectedItemVersion\":" + itemVersion
            + ",\"text\":\"" + text + "\",\"ownerId\":\"" + owner + "\""
            + ",\"updateDueAt\":" + (due == null ? "null" : "\"" + due + "\"") + "}";
    }

    private long acceptedCount(UUID caseId) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM audit_event WHERE case_id = ? AND event_type = 'CASE_ACCEPTED'",
            Long.class, caseId);
    }

    @Test
    void acceptanceNeedsAConfirmedCaseAndADispatcher() throws Exception {
        var f = newDraft("Дом приёма: черновик");
        // A draft belongs to its author alone: the dispatcher must not even learn that it exists.
        acceptance(f, f.dispatcherSession(), UUID.randomUUID().toString(), "{\"expectedCaseVersion\":1}")
            .andExpect(status().isNotFound());
        // The author does see this case, so hiding it would be a lie. The action is refused, not the object.
        acceptance(f, f.authorSession(), UUID.randomUUID().toString(), "{\"expectedCaseVersion\":1}")
            .andExpect(status().isForbidden());
        assertEquals(0L, acceptedCount(f.caseId()), "a refused acceptance must not write an event");
        assertNull(jdbc.queryForObject("SELECT accepted_at FROM service_case WHERE id = ?",
            Timestamp.class, f.caseId()), "a refused acceptance must not stamp the case");
    }

    @Test
    void repeatedAcceptanceDoesNotCreateASecondEvent() throws Exception {
        var f = newDraft("Дом приёма: повтор");
        confirmWithOneItem(f);
        String key = UUID.randomUUID().toString();
        String first = acceptance(f, f.dispatcherSession(), key, "{\"expectedCaseVersion\":3}")
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        Timestamp acceptedFirst = jdbc.queryForObject(
            "SELECT accepted_at FROM service_case WHERE id = ?", Timestamp.class, f.caseId());
        assertNotNull(acceptedFirst);

        // The same key and the same body: the stored response comes back, the database is not touched again.
        String second = acceptance(f, f.dispatcherSession(), key, "{\"expectedCaseVersion\":3}")
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertEquals(first, second, "a replay must return the stored response byte for byte");
        assertEquals(acceptedFirst, jdbc.queryForObject(
                "SELECT accepted_at FROM service_case WHERE id = ?", Timestamp.class, f.caseId()),
            "a replay must not move acceptedAt");
        assertEquals(1L, acceptedCount(f.caseId()), "a replay must not write a second acceptance event");
        var receipt = json.readTree(first).get("receipt");
        assertEquals(f.dispatcher().toString(), receipt.get("acceptedBy").asString());
        assertFalse(receipt.get("confirmedAt").isNull(), "confirmedAt survives acceptance");
    }

    @Test
    void aStaleCaseVersionOnAcceptanceIsAConflict() throws Exception {
        var f = newDraft("Дом приёма: конфликт");
        confirmWithOneItem(f);
        // The dispatcher is not the author. A stale version must still read as 409, never as 404.
        String problem = acceptance(f, f.dispatcherSession(), UUID.randomUUID().toString(),
            "{\"expectedCaseVersion\":2}")
            .andExpect(status().isConflict())
            .andReturn().getResponse().getContentAsString();
        // Four different codes share status 409; a stale version must name the right one.
        assertEquals("CASE_VERSION_CONFLICT", json.readTree(problem).get("code").asString());
        assertEquals(0L, acceptedCount(f.caseId()));
    }

    @Test
    void nextActionChecksTheItemVersionSeparatelyFromTheCase() throws Exception {
        var f = newDraft("Дом шага: версии");
        String confirmed = confirmWithOneItem(f);
        acceptance(f, f.dispatcherSession(), UUID.randomUUID().toString(), "{\"expectedCaseVersion\":3}")
            .andExpect(status().isOk());
        var item = json.readTree(confirmed).get("items").get(0);
        UUID itemId = UUID.fromString(item.get("itemId").asString());
        long itemVersion = item.get("itemVersion").asLong();
        assertTrue(item.get("nextAction").isNull(), "a fresh item carries no plan");
        // A fresh case version with a stale item version: the second guard has to catch it.
        nextAction(f.dispatcherSession(), itemId, UUID.randomUUID().toString(),
            plan(f.dispatcher(), 4, itemVersion - 1, "Вызвать электрика", FUTURE_DUE))
            .andExpect(status().isConflict());
        assertEquals(4L, (long) jdbc.queryForObject("SELECT version FROM service_case WHERE id = ?",
                Long.class, f.caseId()),
            "a rolled back command must not consume a case version");
        String view = nextAction(f.dispatcherSession(), itemId, UUID.randomUUID().toString(),
            plan(f.dispatcher(), 4, itemVersion, "Вызвать электрика", FUTURE_DUE))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        var saved = json.readTree(view).get("items").get(0);
        assertEquals("Вызвать электрика", saved.get("nextAction").get("text").asString());
        assertEquals(f.dispatcher().toString(), saved.get("nextAction").get("ownerId").asString());
        assertEquals(itemVersion + 1, saved.get("itemVersion").asLong(), "the plan bumps the item version");
        assertEquals(item.get("contentVersion").asLong(), saved.get("contentVersion").asLong(),
            "a plan is not content: contentVersion must not move");
        assertEquals(item.get("workRound").asLong(), saved.get("workRound").asLong(),
            "a plan is not a report: workRound must not move");
        assertEquals(5L, json.readTree(view).get("caseVersion").asLong());
    }

    @Test
    void nextActionRejectsADueDateInThePastAndAnOwnerWithoutTheRole() throws Exception {
        var f = newDraft("Дом шага: проверки");
        String confirmed = confirmWithOneItem(f);
        acceptance(f, f.dispatcherSession(), UUID.randomUUID().toString(), "{\"expectedCaseVersion\":3}")
            .andExpect(status().isOk());
        var item = json.readTree(confirmed).get("items").get(0);
        UUID itemId = UUID.fromString(item.get("itemId").asString());
        long itemVersion = item.get("itemVersion").asLong();
        // The clock is fixed at 2027-01-15, so this date is in the past.
        nextAction(f.dispatcherSession(), itemId, UUID.randomUUID().toString(),
            plan(f.dispatcher(), 4, itemVersion, "Поздний план", PAST_DUE))
            .andExpect(status().isUnprocessableEntity());
        // A plan owned by a resident would be a promise nobody can keep.
        nextAction(f.dispatcherSession(), itemId, UUID.randomUUID().toString(),
            plan(f.author(), 4, itemVersion, "Чужой ответственный", FUTURE_DUE))
            .andExpect(status().isUnprocessableEntity());
        assertNull(jdbc.queryForObject("SELECT next_action_text FROM case_item WHERE id = ?",
            String.class, itemId), "a refused plan must not reach the item");
    }

    @Test
    void aResidentCannotSetANextActionOnTheirOwnCase() throws Exception {
        var f = newDraft("Дом шага: житель");
        String confirmed = confirmWithOneItem(f);
        acceptance(f, f.dispatcherSession(), UUID.randomUUID().toString(), "{\"expectedCaseVersion\":3}")
            .andExpect(status().isOk());
        var item = json.readTree(confirmed).get("items").get(0);
        UUID itemId = UUID.fromString(item.get("itemId").asString());
        long itemVersion = item.get("itemVersion").asLong();
        nextAction(f.authorSession(), itemId, UUID.randomUUID().toString(),
            plan(f.dispatcher(), 4, itemVersion, "Сам себе план", FUTURE_DUE))
            .andExpect(status().isForbidden());
        assertNull(jdbc.queryForObject("SELECT next_action_text FROM case_item WHERE id = ?",
            String.class, itemId));
    }

    private UUID createCase(Fixture f, String text) throws Exception {
        String created = mvc.perform(post("/api/v1/cases").session(f.authorSession()).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"houseId\":\"" + f.house() + "\",\"sourceText\":\"" + text + "\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(created).get("caseId").asString());
    }

    /** One door item, then confirmation, for a case other than the fixture's own. */
    private void confirmOneItem(Fixture f, UUID caseId) throws Exception {
        mvc.perform(patch("/api/v1/cases/" + caseId + "/items").session(f.authorSession()).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedCaseVersion\":1,\"items\":[{\"itemId\":null,\"category\":\"ENTRANCE_DOOR\","
                    + "\"location\":\"Вход\",\"symptom\":\"Дверь не закрывается\"}]}"))
            .andExpect(status().isOk());
        mvc.perform(post("/api/v1/cases/" + caseId + "/confirm").session(f.authorSession()).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedCaseVersion\":2,\"sourceRevision\":1}"))
            .andExpect(status().isOk());
    }

    private String queue(MockHttpSession session, UUID house, String state) throws Exception {
        return mvc.perform(get("/api/v1/cases").session(session)
                .param("houseId", house.toString()).param("queueState", state))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    @Test
    void theAcceptanceQueueShowsOnlyUnacceptedConfirmedCases() throws Exception {
        var f = newDraft("Дом очереди: приём");
        confirmWithOneItem(f);
        acceptance(f, f.dispatcherSession(), UUID.randomUUID().toString(), "{\"expectedCaseVersion\":3}")
            .andExpect(status().isOk());
        UUID waiting = createCase(f, "Дверь не закрывается");
        confirmOneItem(f, waiting);
        UUID draft = createCase(f, "Ещё не подтверждено");

        String page = queue(f.dispatcherSession(), f.house(), "NEEDS_ACCEPTANCE");
        assertTrue(page.contains(waiting.toString()), "a confirmed case without acceptance belongs to the queue");
        assertFalse(page.contains(f.caseId().toString()), "an accepted case leaves the queue");
        assertFalse(page.contains(draft.toString()), "an author's draft never reaches a dispatcher queue");
    }

    @Test
    void theActionQueueFollowsAcceptanceAndThePlan() throws Exception {
        var f = newDraft("Дом очереди: действие");
        String confirmed = confirmWithOneItem(f);
        assertFalse(queue(f.dispatcherSession(), f.house(), "NEEDS_ACTION").contains(f.caseId().toString()),
            "a case nobody accepted is not yet waiting for a plan");

        acceptance(f, f.dispatcherSession(), UUID.randomUUID().toString(), "{\"expectedCaseVersion\":3}")
            .andExpect(status().isOk());
        assertTrue(queue(f.dispatcherSession(), f.house(), "NEEDS_ACTION").contains(f.caseId().toString()),
            "an accepted case whose item carries no plan needs one");

        var item = json.readTree(confirmed).get("items").get(0);
        nextAction(f.dispatcherSession(), UUID.fromString(item.get("itemId").asString()),
            UUID.randomUUID().toString(),
            plan(f.dispatcher(), 4, item.get("itemVersion").asLong(), "Вызвать электрика", FUTURE_DUE))
            .andExpect(status().isOk());
        assertFalse(queue(f.dispatcherSession(), f.house(), "NEEDS_ACTION").contains(f.caseId().toString()),
            "a plan with a future update date takes the case out of the queue");

        // These two depend on facts that no command can produce yet; they must answer, not fail.
        assertFalse(queue(f.dispatcherSession(), f.house(), "AWAITING_AUTHOR").contains(f.caseId().toString()));
        assertFalse(queue(f.dispatcherSession(), f.house(), "ARCHIVED").contains(f.caseId().toString()));
    }

    @Test
    void theQueueIsADispatcherToolInsideOneHouse() throws Exception {
        var f = newDraft("Дом очереди: права");
        confirmWithOneItem(f);
        // A resident may list their own cases; a dispatcher queue is a different thing.
        mvc.perform(get("/api/v1/cases").session(f.authorSession())
                .param("houseId", f.house().toString()).param("queueState", "NEEDS_ACCEPTANCE"))
            .andExpect(status().isForbidden());
        // Without a house a queue would cross house boundaries.
        mvc.perform(get("/api/v1/cases").session(f.dispatcherSession())
                .param("queueState", "NEEDS_ACCEPTANCE"))
            .andExpect(status().isUnprocessableEntity());
        // An unknown value must not quietly degrade into "no filter at all".
        mvc.perform(get("/api/v1/cases").session(f.dispatcherSession())
                .param("houseId", f.house().toString()).param("queueState", "URGENT"))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void aPageOfCasesKeepsEveryCaseItsOwnItemsAndText() throws Exception {
        var f = newDraft("Дом страницы");
        confirmWithOneItem(f);
        UUID second = createCase(f, "Дверь не закрывается");
        confirmOneItem(f, second);

        String body = mvc.perform(get("/api/v1/cases").session(f.authorSession())
                .param("houseId", f.house().toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        var page = json.readTree(body).get("items");
        var first = caseNode(page, f.caseId());
        var other = caseNode(page, second);

        // A batched page must not mix one case's projection into another.
        assertEquals("LIGHTING", first.get("items").get(0).get("category").asString());
        assertEquals("ENTRANCE_DOOR", other.get("items").get(0).get("category").asString());
        assertTrue(first.get("sourceText").asString().contains("темно"));
        assertTrue(other.get("sourceText").asString().contains("Дверь"));
        assertFalse(first.get("receipt").get("confirmedAt").isNull(),
            "each case keeps its own confirmedAt");
        assertFalse(other.get("receipt").get("confirmedAt").isNull());
        assertNotEquals(first.get("displayNumber").asString(), other.get("displayNumber").asString());
        assertTrue(first.get("displayNumber").asString().matches("\\d+"),
            "a plain number unless the stand asks for a prefix, never an unconditional ДЕМО-");
    }

    private static tools.jackson.databind.JsonNode caseNode(tools.jackson.databind.JsonNode page, UUID caseId) {        for (var node : page) {
            if (node.get("caseId").asString().equals(caseId.toString())) return node;
        }
        throw new AssertionError("case " + caseId + " is missing from the page");
    }
    @Test
    void nextActionRetryAfterItsDueDateReturnsTheStoredResult() throws Exception {
        var f = newDraft("Дом шага: срок прошёл после успешного сохранения");
        var confirmed = json.readTree(confirmWithOneItem(f));
        var item = confirmed.get("items").get(0);
        UUID itemId = UUID.fromString(item.get("itemId").asString());
        String key = UUID.randomUUID().toString();
        String body = plan(f.dispatcher(), confirmed.get("caseVersion").asLong(),
            item.get("itemVersion").asLong(), "Уточнить время осмотра", clock.instant().plusSeconds(60).toString());
        String saved = nextAction(f.dispatcherSession(), itemId, key, body).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        long events = jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE case_id = ?", Long.class, f.caseId());
        clock.now = clock.instant().plusSeconds(120);
        String replay = nextAction(f.dispatcherSession(), itemId, key, body).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertEquals(saved, replay, "elapsed time cannot invalidate an already committed command");
        assertEquals(events, jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE case_id = ?", Long.class, f.caseId()));
        assertEquals(json.readTree(saved).get("caseVersion").asLong(),
            jdbc.queryForObject("SELECT version FROM service_case WHERE id = ?", Long.class, f.caseId()));
        // A fresh intention with the same expired date must still be rejected.
        nextAction(f.dispatcherSession(), itemId, UUID.randomUUID().toString(), body)
            .andExpect(status().isUnprocessableContent());
        jdbc.update("UPDATE membership SET revoked_at = now() WHERE user_id = ? AND house_id = ?", f.dispatcher(), f.house());
        nextAction(f.dispatcherSession(), itemId, key, body).andExpect(status().isNotFound());
    }

}
