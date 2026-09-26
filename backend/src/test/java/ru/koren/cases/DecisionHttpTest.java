package ru.koren.cases;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S-05, QA-E11: an administrative decision closes one item and never fakes a completed case.
 * Withdrawal counts as withdrawn, an archive stays in the remainder, and neither touches total.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=${DB_TEST_URL}", "spring.datasource.username=${DB_TEST_USER:koren}",
    "spring.datasource.password=${DB_TEST_PASSWORD:koren_local_only}",
    "koren.max.bot-token=k1-test-bot-token:not-a-real-secret"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class DecisionHttpTest {

    private static final String AUTHOR_INIT_DATA =
        "user=%7B%22id%22%3A424242%2C%22first_name%22%3A%22Max%22%2C%22last_name%22%3A%22User%22"
            + "%2C%22username%22%3Anull%2C%22language_code%22%3A%22ru%22%7D&ip=192.168.0.1"
            + "&auth_date=1800000000&query_id=AAE1YmYAAAAANTmv4t3W0h01"
            + "&hash=fa2ff9a4255c0e16b0e5be5a4bc6bded45aadad37b589cebb251fc95d7080ad4";
    private static final String DISPATCHER_INIT_DATA =
        "start_param=ref%3Da%3Db&auth_date=1800000000"
            + "&user=%7B%22id%22%3A31337%2C%22first_name%22%3A%22Eq%22%2C%22last_name%22%3Anull%7D"
            + "&hash=b27c3f005d80d8ccfcca2d27e0dc2d5c33f0be1ee3526adf0945cc854d11cc98";

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.ofEpochSecond(1_800_000_000L), ZoneOffset.UTC);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private final JsonMapper json = JsonMapper.builder().build();

    private record Fixture(MockHttpSession authorSession, MockHttpSession dispatcherSession,
                           UUID author, UUID dispatcher, UUID house) {}

    private UUID login(MockHttpSession session, String initData) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/max").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"initData\":\"" + initData + "\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("userId").asString());
    }

    private Fixture house(String address) throws Exception {
        MockHttpSession authorSession = new MockHttpSession();
        UUID author = login(authorSession, AUTHOR_INIT_DATA);
        MockHttpSession dispatcherSession = new MockHttpSession();
        UUID dispatcher = login(dispatcherSession, DISPATCHER_INIT_DATA);
        UUID house = UUID.randomUUID();
        jdbc.update("INSERT INTO house(id, address) VALUES (?, ?)", house, address);
        jdbc.update("INSERT INTO membership VALUES (?,?,'RESIDENT'), (?,?,'DISPATCHER')",
            author, house, dispatcher, house);
        return new Fixture(authorSession, dispatcherSession, author, dispatcher, house);
    }

    /** A confirmed case with a light item and a door item; returns the confirmed CaseView. */
    private JsonNode confirmedCase(Fixture f) throws Exception {
        String created = mvc.perform(post("/api/v1/cases").session(f.authorSession()).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"houseId\":\"" + f.house()
                    + "\",\"sourceText\":\"На третьем этаже темно, и входная дверь не закрывается.\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        UUID caseId = UUID.fromString(json.readTree(created).get("caseId").asString());
        mvc.perform(patch("/api/v1/cases/" + caseId + "/items").session(f.authorSession()).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedCaseVersion\":1,\"items\":[{\"itemId\":null,\"category\":\"LIGHTING\","
                    + "\"location\":\"Этаж 3\",\"symptom\":\"Не горит свет\"},{\"itemId\":null,"
                    + "\"category\":\"ENTRANCE_DOOR\",\"location\":\"Вход\","
                    + "\"symptom\":\"Дверь не закрывается\"}]}"))
            .andExpect(status().isOk());
        String confirmed = mvc.perform(post("/api/v1/cases/" + caseId + "/confirm")
                .session(f.authorSession()).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedCaseVersion\":2,\"sourceRevision\":1}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return json.readTree(confirmed);
    }

    private JsonNode item(JsonNode view, String category) {
        for (JsonNode candidate : view.get("items")) {
            if (category.equals(candidate.get("category").asString())) return candidate;
        }
        throw new AssertionError("No item of category " + category);
    }

    private ResultActions decide(MockHttpSession session, JsonNode view, JsonNode item,
                                 String action, String reason, String key) throws Exception {
        return mvc.perform(post("/api/v1/items/" + item.get("itemId").asString()
            + "/administrative-decisions").session(session).with(csrf())
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedCaseVersion\":" + view.get("caseVersion").asLong()
                + ",\"expectedItemVersion\":" + item.get("itemVersion").asLong()
                + ",\"action\":\"" + action + "\",\"reason\":\"" + reason + "\"}"));
    }

    /** An independent observation needs no report, so a positive result is available up front. */
    private JsonNode observeRestored(Fixture f, JsonNode view, JsonNode item) throws Exception {
        String body = mvc.perform(post("/api/v1/items/" + item.get("itemId").asString() + "/observations")
                .session(f.authorSession()).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedCaseVersion\":" + view.get("caseVersion").asLong()
                    + ",\"expectedItemVersion\":" + item.get("itemVersion").asLong()
                    + ",\"contentVersion\":" + item.get("contentVersion").asInt()
                    + ",\"workRound\":" + item.get("workRound").asInt()
                    + ",\"value\":\"RESTORED\",\"source\":\"INDEPENDENT\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private void assertProgress(JsonNode view, int total, int confirmed, int withdrawn, int remaining) {
        JsonNode progress = view.get("progress");
        assertEquals(total, progress.get("total").asInt(), "total");
        assertEquals(confirmed, progress.get("confirmed").asInt(), "confirmed");
        assertEquals(withdrawn, progress.get("withdrawn").asInt(), "withdrawn");
        assertEquals(remaining, progress.get("remaining").asInt(), "remaining");
        assertFalse(progress.get("allConfirmed").asBoolean(), "a decision must never report full success");
    }

    @Test
    void withdrawnDoorCountsAsWithdrawnAndLeavesTheLightAlone() throws Exception {
        Fixture f = house("QA-E11 withdraw");
        JsonNode confirmed = confirmedCase(f);
        JsonNode view = observeRestored(f, confirmed, item(confirmed, "LIGHTING"));
        JsonNode after = json.readTree(decide(f.authorSession(), view, item(view, "ENTRANCE_DOOR"),
            "WITHDRAW", "Дверь починили до заявки", UUID.randomUUID().toString())
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertEquals("WITHDRAWN", item(after, "ENTRANCE_DOOR").get("administrativeStatus").asString());
        assertEquals("ACTIVE", item(after, "LIGHTING").get("administrativeStatus").asString());
        assertEquals("RESTORED", item(after, "LIGHTING").get("observation").asString());
        assertProgress(after, 2, 1, 1, 0);
    }

    @Test
    void archivedDoorStaysInTheRemainderInsteadOfBecomingWithdrawn() throws Exception {
        Fixture f = house("QA-E11 archive");
        JsonNode confirmed = confirmedCase(f);
        JsonNode view = observeRestored(f, confirmed, item(confirmed, "LIGHTING"));
        JsonNode after = json.readTree(decide(f.dispatcherSession(), view, item(view, "ENTRANCE_DOOR"),
            "ARCHIVE", "Дубль соседней заявки", UUID.randomUUID().toString())
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertEquals("ARCHIVED", item(after, "ENTRANCE_DOOR").get("administrativeStatus").asString());
        assertProgress(after, 2, 1, 0, 1);
    }

    @Test
    void archiveKeepsAPositiveObservationAlreadyGiven() throws Exception {
        Fixture f = house("QA-E11 archive keeps result");
        JsonNode confirmed = confirmedCase(f);
        JsonNode view = observeRestored(f, confirmed, item(confirmed, "LIGHTING"));
        JsonNode after = json.readTree(decide(f.dispatcherSession(), view, item(view, "LIGHTING"),
            "ARCHIVE", "Работы приняты, пункт закрыт", UUID.randomUUID().toString())
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertEquals("RESTORED", item(after, "LIGHTING").get("observation").asString(),
            "an archive must not erase a result the author already reported");
        assertProgress(after, 2, 1, 0, 1);
    }

    @Test
    void withdrawalInventsNoObservation() throws Exception {
        Fixture f = house("QA-E11 withdraw is not success");
        JsonNode view = confirmedCase(f);
        JsonNode after = json.readTree(decide(f.authorSession(), view, item(view, "ENTRANCE_DOOR"),
            "WITHDRAW", "Передумал", UUID.randomUUID().toString())
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertEquals("NOT_RECEIVED", item(after, "ENTRANCE_DOOR").get("observation").asString());
        assertProgress(after, 2, 0, 1, 1);
    }

    @Test
    void dispatcherMayNotWithdrawSomebodyElsesItem() throws Exception {
        Fixture f = house("QA-E11 dispatcher withdraw");
        JsonNode view = confirmedCase(f);
        decide(f.dispatcherSession(), view, item(view, "ENTRANCE_DOOR"),
            "WITHDRAW", "Не моё решение", UUID.randomUUID().toString())
            .andExpect(status().isForbidden());
    }

    @Test
    void authorMayNotArchive() throws Exception {
        Fixture f = house("QA-E11 author archive");
        JsonNode view = confirmedCase(f);
        decide(f.authorSession(), view, item(view, "ENTRANCE_DOOR"),
            "ARCHIVE", "Хочу в архив", UUID.randomUUID().toString())
            .andExpect(status().isForbidden());
    }

    @Test
    void aSecondDecisionOnTheSameItemIsRejected() throws Exception {
        Fixture f = house("QA-E11 second decision");
        JsonNode view = confirmedCase(f);
        JsonNode after = json.readTree(decide(f.authorSession(), view, item(view, "ENTRANCE_DOOR"),
            "WITHDRAW", "Первое и единственное", UUID.randomUUID().toString())
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        decide(f.dispatcherSession(), after, item(after, "ENTRANCE_DOOR"),
            "ARCHIVE", "Поздно", UUID.randomUUID().toString())
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void aBlankReasonIsRejected() throws Exception {
        Fixture f = house("QA-E11 blank reason");
        JsonNode view = confirmedCase(f);
        decide(f.authorSession(), view, item(view, "ENTRANCE_DOOR"),
            "WITHDRAW", "   ", UUID.randomUUID().toString())
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void anUnknownActionIsRejected() throws Exception {
        Fixture f = house("QA-E11 unknown action");
        JsonNode view = confirmedCase(f);
        decide(f.authorSession(), view, item(view, "ENTRANCE_DOOR"),
            "DELETE", "Удалить совсем", UUID.randomUUID().toString())
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void aStaleItemVersionConflicts() throws Exception {
        Fixture f = house("QA-E11 stale item version");
        JsonNode view = confirmedCase(f);
        JsonNode door = item(view, "ENTRANCE_DOOR");
        mvc.perform(post("/api/v1/items/" + door.get("itemId").asString() + "/administrative-decisions")
                .session(f.authorSession()).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedCaseVersion\":" + view.get("caseVersion").asLong()
                    + ",\"expectedItemVersion\":" + (door.get("itemVersion").asLong() + 5)
                    + ",\"action\":\"WITHDRAW\",\"reason\":\"Версия из прошлого\"}"))
            .andExpect(status().isConflict());
    }

    @Test
    void repeatingTheCommandWithTheSameKeyStoresOneEventOnly() throws Exception {
        Fixture f = house("QA-E11 idempotent decision");
        JsonNode view = confirmedCase(f);
        String key = UUID.randomUUID().toString();
        decide(f.authorSession(), view, item(view, "ENTRANCE_DOOR"), "WITHDRAW", "Один раз", key)
            .andExpect(status().isOk());
        JsonNode replay = json.readTree(
            decide(f.authorSession(), view, item(view, "ENTRANCE_DOOR"), "WITHDRAW", "Один раз", key)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertEquals("WITHDRAWN", item(replay, "ENTRANCE_DOOR").get("administrativeStatus").asString());
        Integer events = jdbc.queryForObject(
            "SELECT count(*) FROM audit_event WHERE case_id = ? AND event_type = 'ITEM_ADMINISTRATIVE_DECISION'",
            Integer.class, UUID.fromString(view.get("caseId").asString()));
        assertEquals(1, events, "a replayed command must not write a second event");
    }
}
