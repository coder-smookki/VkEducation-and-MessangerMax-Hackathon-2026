package ru.koren.observations;

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
 * S-05: the author's answer about one item. Only the author, only the current round
 * and content. NOT_RECEIVED is the absence of an answer, never a value a command carries.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=${DB_TEST_URL}", "spring.datasource.username=${DB_TEST_USER:koren}",
    "spring.datasource.password=${DB_TEST_PASSWORD:koren_local_only}",
    "koren.max.bot-token=k1-test-bot-token:not-a-real-secret"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class ObservationHttpTest {

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

    private ResultActions report(MockHttpSession session, UUID caseId, String key, String body) throws Exception {
        return mvc.perform(post("/api/v1/cases/" + caseId + "/reports").session(session).with(csrf())
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String body(long caseVersion, String text, UUID... itemIds) {
        var ids = new StringBuilder();
        for (UUID id : itemIds) {
            if (ids.length() > 0) ids.append(',');
            ids.append('"').append(id).append('"');
        }
        return "{\"expectedCaseVersion\":" + caseVersion + ",\"reportText\":\"" + text
            + "\",\"targetItemIds\":[" + ids + "]}";
    }

    private static UUID itemId(JsonNode caseView, String category) {
        for (var item : caseView.get("items")) {
            if (item.get("category").asString().equals(category)) {
                return UUID.fromString(item.get("itemId").asString());
            }
        }
        throw new AssertionError("no item of category " + category);
    }

    private static JsonNode item(JsonNode caseView, UUID itemId) {
        for (var item : caseView.get("items")) {
            if (item.get("itemId").asString().equals(itemId.toString())) return item;
        }
        throw new AssertionError("item " + itemId + " is missing");
    }

    private ResultActions links(MockHttpSession session, UUID reportId, String key, String body)
        throws Exception {
        return mvc.perform(post("/api/v1/reports/" + reportId + "/links").session(session).with(csrf())
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String link(JsonNode item, String workStatus) {
        return "{\"itemId\":\"" + item.get("itemId").asString()
            + "\",\"expectedItemVersion\":" + item.get("itemVersion").asLong()
            + ",\"contentVersion\":" + item.get("contentVersion").asInt()
            + ",\"expectedWorkRound\":" + item.get("workRound").asInt()
            + ",\"workStatus\":\"" + workStatus + "\"}";
    }

    private static String linksBody(long caseVersion, String... fragments) {
        return "{\"expectedCaseVersion\":" + caseVersion + ",\"links\":[" + String.join(",", fragments) + "]}";
    }

    private record Saved(UUID caseId, UUID reportId, JsonNode caseAfterReport) {}

    private Saved savedReport(Fixture f, String text) throws Exception {
        var confirmed = confirmedCase(f);
        UUID caseId = UUID.fromString(confirmed.get("caseId").asString());
        String created = report(f.dispatcherSession(), caseId, UUID.randomUUID().toString(),
            body(confirmed.get("caseVersion").asLong(), text,
                itemId(confirmed, "LIGHTING"), itemId(confirmed, "ENTRANCE_DOOR")))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var response = json.readTree(created);
        return new Saved(caseId,
            UUID.fromString(response.get("report").get("reportId").asString()),
            response.get("case"));
    }

    private ResultActions observe(MockHttpSession session, UUID itemId, String key, String body)
        throws Exception {
        return mvc.perform(post("/api/v1/items/" + itemId + "/observations").session(session).with(csrf())
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String answer(long caseVersion, JsonNode item, String value, String source) {
        return "{\"expectedCaseVersion\":" + caseVersion
            + ",\"expectedItemVersion\":" + item.get("itemVersion").asLong()
            + ",\"contentVersion\":" + item.get("contentVersion").asInt()
            + ",\"workRound\":" + item.get("workRound").asInt()
            + ",\"value\":\"" + value + "\",\"source\":\"" + source + "\"}";
    }

    /** A saved report plus a confirmed REPORTED_DONE link on the light; returns the case after the link. */
    private JsonNode reportedLight(Fixture f, Saved saved) throws Exception {
        var before = saved.caseAfterReport();
        UUID light = itemId(before, "LIGHTING");
        String result = links(f.dispatcherSession(), saved.reportId(), UUID.randomUUID().toString(),
            linksBody(before.get("caseVersion").asLong(), link(item(before, light), "REPORTED_DONE")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(result);
    }

    @Test
    void theAuthorAnswersForTheCurrentRoundAndProgressMoves() throws Exception {
        var f = house("Дом наблюдения: ответ");
        var saved = savedReport(f, "Лампу заменили");
        var afterLink = reportedLight(f, saved);
        UUID light = itemId(afterLink, "LIGHTING");
        UUID door = itemId(afterLink, "ENTRANCE_DOOR");
        assertEquals("NOT_RECEIVED", item(afterLink, light).get("observation").asString(),
            "a new round starts without an answer");

        String result = observe(f.authorSession(), light, UUID.randomUUID().toString(),
            answer(afterLink.get("caseVersion").asLong(), item(afterLink, light),
                "RESTORED", "AFTER_REPORT"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var after = json.readTree(result);

        var lightAfter = item(after, light);
        assertEquals("RESTORED", lightAfter.get("observation").asString());
        assertEquals(item(afterLink, light).get("itemVersion").asLong() + 1,
            lightAfter.get("itemVersion").asLong());
        assertEquals(item(afterLink, light).get("workRound").asInt(), lightAfter.get("workRound").asInt(),
            "an answer is not a new round");
        assertEquals("REPORTED_DONE", lightAfter.get("workStatus").asString(),
            "an answer does not rewrite what the staff reported");

        // The author's positive answer, not the staff report, is what counts as confirmed.
        assertEquals(1, after.get("progress").get("confirmed").asInt());
        assertFalse(after.get("progress").get("allConfirmed").asBoolean(),
            "the door has no positive answer, so the case is not done");
        assertEquals(item(afterLink, door).get("observation").asString(),
            item(after, door).get("observation").asString(), "the door was not touched");

        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT count(*) FROM resident_observation WHERE item_id = ?", Integer.class, light),
            "every answer is kept in history, not only the current projection");
    }

    @Test
    void anAnswerToAnOldRoundIsRefused() throws Exception {
        var f = house("Дом наблюдения: старый раунд");
        var saved = savedReport(f, "Лампу заменили");
        var before = saved.caseAfterReport();
        UUID light = itemId(before, "LIGHTING");
        var afterLink = reportedLight(f, saved);

        // The dispatcher opened round 1 meanwhile; an answer built for round 0 must not land on it.
        String problem = observe(f.authorSession(), light, UUID.randomUUID().toString(),
            answer(afterLink.get("caseVersion").asLong(), item(before, light), "RESTORED", "AFTER_REPORT"))
            .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        assertEquals("STALE_CONTEXT", json.readTree(problem).get("code").asString());
        assertEquals("NOT_RECEIVED", (String) jdbc.queryForObject(
            "SELECT observation FROM case_item WHERE id = ?", String.class, light));
        assertEquals(0, (int) jdbc.queryForObject(
            "SELECT count(*) FROM resident_observation WHERE item_id = ?", Integer.class, light));
    }

    @Test
    void afterReportNeedsAReportForThisRound() throws Exception {
        var f = house("Дом наблюдения: без отчёта");
        var saved = savedReport(f, "Про свет");
        var afterLink = reportedLight(f, saved);
        UUID door = itemId(afterLink, "ENTRANCE_DOOR");

        // Nobody reported anything about the door, so there is nothing to answer about.
        observe(f.authorSession(), door, UUID.randomUUID().toString(),
            answer(afterLink.get("caseVersion").asLong(), item(afterLink, door),
                "RESTORED", "AFTER_REPORT"))
            .andExpect(status().isUnprocessableEntity());
        assertEquals(0, (int) jdbc.queryForObject(
            "SELECT count(*) FROM resident_observation WHERE item_id = ?", Integer.class, door));
    }

    @Test
    void anIndependentAnswerNeedsNoReport() throws Exception {
        var f = house("Дом наблюдения: независимо");
        var saved = savedReport(f, "Про свет");
        var afterLink = reportedLight(f, saved);
        UUID door = itemId(afterLink, "ENTRANCE_DOOR");

        String result = observe(f.authorSession(), door, UUID.randomUUID().toString(),
            answer(afterLink.get("caseVersion").asLong(), item(afterLink, door),
                "PERSISTS", "INDEPENDENT"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var doorAfter = item(json.readTree(result), door);

        assertEquals("PERSISTS", doorAfter.get("observation").asString());
        assertEquals("NOT_ASSIGNED", doorAfter.get("workStatus").asString(),
            "an independent answer says nothing about work");
        assertEquals(0, doorAfter.get("workRound").asInt());
        assertEquals(0, json.readTree(result).get("progress").get("confirmed").asInt(),
            "PERSISTS is an answer, not a confirmation");
    }

    @Test
    void onlyTheAuthorAnswers() throws Exception {
        var f = house("Дом наблюдения: права");
        var saved = savedReport(f, "Лампу заменили");
        var afterLink = reportedLight(f, saved);
        UUID light = itemId(afterLink, "LIGHTING");

        // The dispatcher reported the work; judging the result is not their call.
        observe(f.dispatcherSession(), light, UUID.randomUUID().toString(),
            answer(afterLink.get("caseVersion").asLong(), item(afterLink, light),
                "RESTORED", "AFTER_REPORT"))
            .andExpect(status().isForbidden());
        assertEquals("NOT_RECEIVED", (String) jdbc.queryForObject(
            "SELECT observation FROM case_item WHERE id = ?", String.class, light));
    }

    @Test
    void notReceivedIsNotAnAnswer() throws Exception {
        var f = house("Дом наблюдения: пустое значение");
        var saved = savedReport(f, "Лампу заменили");
        var afterLink = reportedLight(f, saved);
        UUID light = itemId(afterLink, "LIGHTING");

        observe(f.authorSession(), light, UUID.randomUUID().toString(),
            answer(afterLink.get("caseVersion").asLong(), item(afterLink, light),
                "NOT_RECEIVED", "AFTER_REPORT"))
            .andExpect(status().isUnprocessableEntity());
        assertEquals(0, (int) jdbc.queryForObject(
            "SELECT count(*) FROM resident_observation WHERE item_id = ?", Integer.class, light));
    }
}
