package ru.koren.notifications;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S-05: commands queue their own news, in their own transaction, for the other side only.
 * This is the test that would catch the wiring being absent; the queue's own behaviour is
 * covered by OutboxWorkerTest.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=${DB_TEST_URL}", "spring.datasource.username=${DB_TEST_USER:koren}",
    "spring.datasource.password=${DB_TEST_PASSWORD:koren_local_only}",
    "koren.max.bot-token=k1-test-bot-token:not-a-real-secret"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class OutboxWiringHttpTest {

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

    private MockHttpSession authorSession;
    private MockHttpSession dispatcherSession;
    private UUID author;
    private UUID dispatcher;
    private UUID house;

    private UUID login(MockHttpSession session, String initData) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/max").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"initData\":\"" + initData + "\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("userId").asString());
    }

    /** Confirms a two-item case and returns the confirmed view. */
    private JsonNode confirmedCase() throws Exception {
        authorSession = new MockHttpSession();
        author = login(authorSession, AUTHOR_INIT_DATA);
        dispatcherSession = new MockHttpSession();
        dispatcher = login(dispatcherSession, DISPATCHER_INIT_DATA);
        house = UUID.randomUUID();
        jdbc.update("INSERT INTO house(id, address) VALUES (?, ?)", house, "Wiring " + house);
        jdbc.update("INSERT INTO membership VALUES (?,?,'RESIDENT'), (?,?,'DISPATCHER')",
            author, house, dispatcher, house);
        String created = mvc.perform(post("/api/v1/cases").session(authorSession).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"houseId\":\"" + house
                    + "\",\"sourceText\":\"На третьем этаже темно, и входная дверь не закрывается.\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        UUID caseId = UUID.fromString(json.readTree(created).get("caseId").asString());
        mvc.perform(patch("/api/v1/cases/" + caseId + "/items").session(authorSession).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedCaseVersion\":1,\"items\":[{\"itemId\":null,\"category\":\"LIGHTING\","
                    + "\"location\":\"Этаж 3\",\"symptom\":\"Не горит свет\"},{\"itemId\":null,"
                    + "\"category\":\"ENTRANCE_DOOR\",\"location\":\"Вход\","
                    + "\"symptom\":\"Дверь не закрывается\"}]}"))
            .andExpect(status().isOk());
        String confirmed = mvc.perform(post("/api/v1/cases/" + caseId + "/confirm")
                .session(authorSession).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedCaseVersion\":2,\"sourceRevision\":1}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return json.readTree(confirmed);
    }

    private int queued(UUID caseId, String eventType, UUID recipient) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_event"
                + " WHERE case_id = ? AND event_type = ? AND recipient_id = ?",
            Integer.class, caseId, eventType, recipient);
    }

    private JsonNode item(JsonNode view, String category) {
        for (JsonNode candidate : view.get("items")) {
            if (category.equals(candidate.get("category").asString())) return candidate;
        }
        throw new AssertionError("No item of category " + category);
    }

    @Test
    void confirmingTellsTheDispatcherAndNotTheAuthorWhoDidIt() throws Exception {
        JsonNode view = confirmedCase();
        UUID caseId = UUID.fromString(view.get("caseId").asString());

        assertEquals(1, queued(caseId, "CASE_CONFIRMED", dispatcher));
        assertEquals(0, queued(caseId, "CASE_CONFIRMED", author),
            "nobody needs news of their own command");
    }

    @Test
    void acceptingTellsTheAuthorAndARepeatDoesNotTellHimTwice() throws Exception {
        JsonNode view = confirmedCase();
        UUID caseId = UUID.fromString(view.get("caseId").asString());
        String key = UUID.randomUUID().toString();
        String body = "{\"expectedCaseVersion\":" + view.get("caseVersion").asLong() + "}";
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/v1/cases/" + caseId + "/acceptance")
                    .session(dispatcherSession).with(csrf())
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        }

        assertEquals(1, queued(caseId, "CASE_ACCEPTED", author));
        assertEquals(0, queued(caseId, "CASE_ACCEPTED", dispatcher));
    }

    @Test
    void withdrawingTellsTheDispatcherWhileArchivingTellsTheAuthor() throws Exception {
        JsonNode view = confirmedCase();
        UUID caseId = UUID.fromString(view.get("caseId").asString());
        JsonNode door = item(view, "ENTRANCE_DOOR");
        JsonNode light = item(view, "LIGHTING");

        mvc.perform(post("/api/v1/items/" + door.get("itemId").asString() + "/administrative-decisions")
                .session(authorSession).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedCaseVersion\":" + view.get("caseVersion").asLong()
                    + ",\"expectedItemVersion\":" + door.get("itemVersion").asLong()
                    + ",\"action\":\"WITHDRAW\",\"reason\":\"Починили до заявки\"}"))
            .andExpect(status().isOk());
        String afterWithdraw = mvc.perform(get("/api/v1/cases/" + caseId).session(authorSession))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        mvc.perform(post("/api/v1/items/" + light.get("itemId").asString() + "/administrative-decisions")
                .session(dispatcherSession).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedCaseVersion\":" + json.readTree(afterWithdraw).get("caseVersion").asLong()
                    + ",\"expectedItemVersion\":" + light.get("itemVersion").asLong()
                    + ",\"action\":\"ARCHIVE\",\"reason\":\"Дубль соседней заявки\"}"))
            .andExpect(status().isOk());

        assertEquals(1, queued(caseId, "ITEM_WITHDRAWN", dispatcher));
        assertEquals(0, queued(caseId, "ITEM_WITHDRAWN", author));
        assertEquals(1, queued(caseId, "ITEM_ARCHIVED", author));
        assertEquals(0, queued(caseId, "ITEM_ARCHIVED", dispatcher));
    }

    @Test
    void aQueuedNotificationCarriesNoUserText() throws Exception {
        JsonNode view = confirmedCase();
        UUID caseId = UUID.fromString(view.get("caseId").asString());
        JsonNode door = item(view, "ENTRANCE_DOOR");
        mvc.perform(post("/api/v1/items/" + door.get("itemId").asString() + "/administrative-decisions")
                .session(authorSession).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedCaseVersion\":" + view.get("caseVersion").asLong()
                    + ",\"expectedItemVersion\":" + door.get("itemVersion").asLong()
                    + ",\"action\":\"WITHDRAW\",\"reason\":\"Секретная причина отзыва\"}"))
            .andExpect(status().isOk());

        String payloads = String.join("\n", jdbc.queryForList(
            "SELECT payload::text FROM outbox_event WHERE case_id = ?", String.class, caseId));
        assertFalse(payloads.contains("Секретная"),
            "a reason must not travel to MAX: access can be revoked before delivery");
        assertFalse(payloads.contains("Дверь не закрывается"), "no item text in a notification");
    }
}
