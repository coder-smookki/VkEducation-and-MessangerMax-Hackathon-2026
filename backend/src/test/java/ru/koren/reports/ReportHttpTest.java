package ru.koren.reports;

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
 * S-04, first half: saving a work report stores text and scope and touches no item.
 * A round starts only when a dispatcher confirms a link, which is the second half.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=${DB_TEST_URL}", "spring.datasource.username=${DB_TEST_USER:koren}",
    "spring.datasource.password=${DB_TEST_PASSWORD:koren_local_only}",
    "koren.max.bot-token=k1-test-bot-token:not-a-real-secret"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class ReportHttpTest {

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

    @Test
    void savingAReportMovesNoItem() throws Exception {
        var f = house("Дом отчёта: сохранение");
        var before = confirmedCase(f);
        UUID caseId = UUID.fromString(before.get("caseId").asString());
        UUID light = itemId(before, "LIGHTING");
        UUID door = itemId(before, "ENTRANCE_DOOR");

        String created = report(f.dispatcherSession(), caseId, UUID.randomUUID().toString(),
            body(before.get("caseVersion").asLong(), "Заменили лампу, дверь посмотрим позже", light, door))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        var response = json.readTree(created);

        var report = response.get("report");
        assertEquals("Заменили лампу, дверь посмотрим позже", report.get("reportText").asString());
        assertEquals(f.dispatcher().toString(), report.get("actorId").asString());
        assertEquals(2, report.get("targetItemIds").size());
        assertEquals(0, report.get("links").size(), "a fresh report carries no confirmed link");
        assertEquals("MANUAL_REQUIRED", response.get("analysis").get("status").asString(),
            "no analysis is wired, and the answer says so");

        // The whole point of S-04: text alone moves nothing.
        var after = response.get("case");
        for (UUID id : new UUID[]{light, door}) {
            assertEquals(item(before, id).get("workRound").asLong(), item(after, id).get("workRound").asLong());
            assertEquals(item(before, id).get("workStatus").asString(), item(after, id).get("workStatus").asString());
            assertEquals(item(before, id).get("observation").asString(),
                item(after, id).get("observation").asString());
            assertEquals(item(before, id).get("itemVersion").asLong(), item(after, id).get("itemVersion").asLong());
        }
        assertEquals(before.get("caseVersion").asLong() + 1, after.get("caseVersion").asLong(),
            "the case gained a report, so its own version moves");
        assertEquals(0, (int) jdbc.queryForObject(
                "SELECT count(*) FROM report_item_link WHERE case_id = ?", Integer.class, caseId),
            "saving text must not create a link");
    }

    @Test
    void aRepeatedReportKeyReturnsTheStoredReport() throws Exception {
        var f = house("Дом отчёта: повтор");
        var confirmed = confirmedCase(f);
        UUID caseId = UUID.fromString(confirmed.get("caseId").asString());
        UUID light = itemId(confirmed, "LIGHTING");
        String key = UUID.randomUUID().toString();
        String payload = body(confirmed.get("caseVersion").asLong(), "Лампа заменена", light);

        String first = report(f.dispatcherSession(), caseId, key, payload)
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = report(f.dispatcherSession(), caseId, key, payload)
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        assertEquals(first, second, "a replay must return the stored response");
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT count(*) FROM work_report WHERE case_id = ?", Integer.class, caseId),
            "a replay must not store a second report");
    }

    @Test
    void onlyADispatcherOfTheHouseSavesAReport() throws Exception {
        var f = house("Дом отчёта: права");
        var confirmed = confirmedCase(f);
        UUID caseId = UUID.fromString(confirmed.get("caseId").asString());
        UUID light = itemId(confirmed, "LIGHTING");
        long version = confirmed.get("caseVersion").asLong();

        // The author sees the case, so the object is not hidden; the action is refused.
        report(f.authorSession(), caseId, UUID.randomUUID().toString(), body(version, "Сам себе отчёт", light))
            .andExpect(status().isForbidden());
        assertEquals(0, (int) jdbc.queryForObject(
            "SELECT count(*) FROM work_report WHERE case_id = ?", Integer.class, caseId));
    }

    @Test
    void targetItemsMustBelongToThisCase() throws Exception {
        var f = house("Дом отчёта: чужой пункт");
        var first = confirmedCase(f);
        var other = confirmedCase(f);
        UUID caseId = UUID.fromString(first.get("caseId").asString());
        UUID foreignItem = itemId(other, "LIGHTING");

        report(f.dispatcherSession(), caseId, UUID.randomUUID().toString(),
            body(first.get("caseVersion").asLong(), "Отчёт о чужом пункте", foreignItem))
            .andExpect(status().isUnprocessableEntity());
        assertEquals(0, (int) jdbc.queryForObject(
            "SELECT count(*) FROM work_report WHERE case_id = ?", Integer.class, caseId));
    }

    @Test
    void theStoredReportIsReadableByItsCaseReaders() throws Exception {
        var f = house("Дом отчёта: чтение");
        var confirmed = confirmedCase(f);
        UUID caseId = UUID.fromString(confirmed.get("caseId").asString());
        UUID door = itemId(confirmed, "ENTRANCE_DOOR");

        String created = report(f.dispatcherSession(), caseId, UUID.randomUUID().toString(),
            body(confirmed.get("caseVersion").asLong(), "Дверь осмотрена", door))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID reportId = UUID.fromString(json.readTree(created).get("report").get("reportId").asString());

        // The author of the case may read a report about it, and sees no link yet.
        String read = mvc.perform(get("/api/v1/reports/" + reportId).session(f.authorSession()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var view = json.readTree(read);
        assertEquals("Дверь осмотрена", view.get("reportText").asString());
        assertEquals(door.toString(), view.get("targetItemIds").get(0).asString());
        assertEquals(0, view.get("links").size());
    }
}
