package ru.koren.cases;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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

/** S-05: the case history is ascending, paginated and carries allow-listed fields only. */
@SpringBootTest(properties = {
    "spring.datasource.url=${DB_TEST_URL}", "spring.datasource.username=${DB_TEST_USER:koren}",
    "spring.datasource.password=${DB_TEST_PASSWORD:koren_local_only}",
    "koren.max.bot-token=k1-test-bot-token:not-a-real-secret"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class HistoryHttpTest {

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

    /** Creates, edits, confirms, accepts and withdraws: five event types in one case. */
    private JsonNode busyCase(Fixture f) throws Exception {
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
        String accepted = mvc.perform(post("/api/v1/cases/" + caseId + "/acceptance")
                .session(f.dispatcherSession()).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedCaseVersion\":" + json.readTree(confirmed).get("caseVersion").asLong() + "}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JsonNode view = json.readTree(accepted);
        JsonNode door = null;
        for (JsonNode candidate : view.get("items")) {
            if ("ENTRANCE_DOOR".equals(candidate.get("category").asString())) door = candidate;
        }
        String withdrawn = mvc.perform(post("/api/v1/items/" + door.get("itemId").asString()
                + "/administrative-decisions").session(f.authorSession()).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedCaseVersion\":" + view.get("caseVersion").asLong()
                    + ",\"expectedItemVersion\":" + door.get("itemVersion").asLong()
                    + ",\"action\":\"WITHDRAW\",\"reason\":\"Дверь починили до заявки\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return json.readTree(withdrawn);
    }

    private JsonNode history(MockHttpSession session, UUID caseId, String query) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/cases/" + caseId + "/history" + query).session(session))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void historyIsAscendingAndNamesEveryCommandThatRan() throws Exception {
        Fixture f = house("QA history order");
        UUID caseId = UUID.fromString(busyCase(f).get("caseId").asString());
        JsonNode page = history(f.authorSession(), caseId, "");

        List<String> types = new ArrayList<>();
        List<String> times = new ArrayList<>();
        for (JsonNode event : page.get("items")) {
            types.add(event.get("eventType").asString());
            times.add(event.get("createdAt").asString());
        }
        assertEquals(List.of("CASE_CREATED", "CASE_ITEMS_PATCHED", "CASE_CONFIRMED",
            "CASE_ACCEPTED", "ITEM_ADMINISTRATIVE_DECISION"), types);
        assertEquals(times.stream().sorted().toList(), times, "history must read forward in time");
        assertTrue(page.get("nextCursor").isNull());
    }

    @Test
    void detailsCarryAllowListedFieldsOnly() throws Exception {
        Fixture f = house("QA history details");
        UUID caseId = UUID.fromString(busyCase(f).get("caseId").asString());
        JsonNode page = history(f.authorSession(), caseId, "");

        for (JsonNode event : page.get("items")) {
            JsonNode details = event.get("details");
            assertFalse(details.has("caseId"), "the path already carries the case");
            assertFalse(details.has("reason"), "a decision reason never reaches history details");
            assertFalse(details.has("sourceText"), "no free text in history details");
            if ("ITEM_ADMINISTRATIVE_DECISION".equals(event.get("eventType").asString())) {
                assertEquals("WITHDRAW", details.get("action").asString());
                assertTrue(details.has("itemId"));
                assertEquals(2, details.size(), "only itemId and action are allowed here");
            }
        }
    }

    @Test
    void theCursorWalksTheWholeHistoryWithoutGapsOrRepeats() throws Exception {
        Fixture f = house("QA history paging");
        UUID caseId = UUID.fromString(busyCase(f).get("caseId").asString());

        List<String> walked = new ArrayList<>();
        String cursor = null;
        for (int guard = 0; guard < 10; guard++) {
            JsonNode page = history(f.authorSession(), caseId,
                "?limit=2" + (cursor == null ? "" : "&cursor=" + cursor));
            for (JsonNode event : page.get("items")) walked.add(event.get("eventId").asString());
            if (page.get("nextCursor").isNull()) break;
            cursor = page.get("nextCursor").asString();
        }

        JsonNode whole = history(f.authorSession(), caseId, "");
        List<String> expected = new ArrayList<>();
        for (JsonNode event : whole.get("items")) expected.add(event.get("eventId").asString());
        assertEquals(expected, walked);
        assertEquals(expected.size(), walked.stream().distinct().count(), "no event may repeat across pages");
    }

    @Test
    void theDispatcherOfTheHouseSeesTheSameHistory() throws Exception {
        Fixture f = house("QA history dispatcher");
        UUID caseId = UUID.fromString(busyCase(f).get("caseId").asString());
        assertEquals(history(f.authorSession(), caseId, "").get("items").size(),
            history(f.dispatcherSession(), caseId, "").get("items").size());
    }

    @Test
    void aStrangerGetsNotFoundInsteadOfAnEmptyPage() throws Exception {
        Fixture f = house("QA history stranger");
        UUID caseId = UUID.fromString(busyCase(f).get("caseId").asString());
        MockHttpSession stranger = new MockHttpSession();
        login(stranger, DISPATCHER_INIT_DATA);
        jdbc.update("UPDATE membership SET revoked_at = now() WHERE user_id = ? AND house_id = ?",
            f.dispatcher(), f.house());

        mvc.perform(get("/api/v1/cases/" + caseId + "/history").session(stranger))
            .andExpect(status().isNotFound());
    }

    @Test
    void aCursorFromAnotherCaseIsRefused() throws Exception {
        Fixture f = house("QA history foreign cursor");
        UUID first = UUID.fromString(busyCase(f).get("caseId").asString());
        UUID second = UUID.fromString(busyCase(f).get("caseId").asString());
        String cursor = history(f.authorSession(), first, "?limit=2").get("nextCursor").asString();

        mvc.perform(get("/api/v1/cases/" + second + "/history?limit=2&cursor=" + cursor)
                .session(f.authorSession()))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void anOversizedLimitIsRefused() throws Exception {
        Fixture f = house("QA history limit");
        UUID caseId = UUID.fromString(busyCase(f).get("caseId").asString());
        mvc.perform(get("/api/v1/cases/" + caseId + "/history?limit=51").session(f.authorSession()))
            .andExpect(status().isUnprocessableEntity());
    }
}
