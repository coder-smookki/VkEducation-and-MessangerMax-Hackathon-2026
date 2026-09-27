package ru.koren.cases;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The whole draft flow over HTTP. Author A and stranger B are two really signed MAX users. */
@SpringBootTest(properties = {
    "spring.datasource.url=${DB_TEST_URL}", "spring.datasource.username=${DB_TEST_USER:koren}",
    "spring.datasource.password=${DB_TEST_PASSWORD:koren_local_only}",
    "koren.max.bot-token=k1-test-bot-token:not-a-real-secret"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class CaseHttpTest {

    private static final String AUTHOR_INIT_DATA =
        "user=%7B%22id%22%3A424242%2C%22first_name%22%3A%22Max%22%2C%22last_name%22%3A%22User%22"
            + "%2C%22username%22%3Anull%2C%22language_code%22%3A%22ru%22%7D&ip=192.168.0.1"
            + "&auth_date=1800000000&query_id=AAE1YmYAAAAANTmv4t3W0h01"
            + "&hash=fa2ff9a4255c0e16b0e5be5a4bc6bded45aadad37b589cebb251fc95d7080ad4";
    private static final String STRANGER_INIT_DATA =
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

    private UUID login(MockHttpSession session, String initData) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/max").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"initData\":\"" + initData + "\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("userId").asString());
    }

    @Test
    void draftFlowOverHttpAndStrangerSeesNothing() throws Exception {
        MockHttpSession authorSession = new MockHttpSession();
        UUID author = login(authorSession, AUTHOR_INIT_DATA);
        MockHttpSession strangerSession = new MockHttpSession();
        UUID stranger = login(strangerSession, STRANGER_INIT_DATA);
        UUID house = UUID.randomUUID();
        jdbc.update("INSERT INTO house(id, address) VALUES (?, 'Учебный дом HTTP')", house);
        jdbc.update("INSERT INTO membership VALUES (?,?,'RESIDENT'), (?,?,'RESIDENT')",
            author, house, stranger, house);
        String created = mvc.perform(post("/api/v1/cases").session(authorSession).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"houseId\":\"" + house + "\",\"sourceText\":\"Темно на третьем этаже\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        UUID caseId = UUID.fromString(json.readTree(created).get("caseId").asString());
        mvc.perform(get("/api/v1/cases/" + caseId).session(authorSession)).andExpect(status().isOk());
        // A neighbour of the same house must not reach someone else's draft.
        mvc.perform(get("/api/v1/cases/" + caseId).session(strangerSession)).andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/cases/" + caseId + "/items").session(authorSession).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedCaseVersion\":1,\"items\":[{\"itemId\":null,\"category\":\"LIGHTING\","
                    + "\"location\":\"Этаж 3\",\"symptom\":\"Не горит свет\"}]}"))
            .andExpect(status().isOk());
        // A stale version must not silently overwrite the current draft.
        mvc.perform(post("/api/v1/cases/" + caseId + "/confirm").session(authorSession).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedCaseVersion\":1,\"sourceRevision\":1}"))
            .andExpect(status().isConflict());
        String confirmed = mvc.perform(post("/api/v1/cases/" + caseId + "/confirm").session(authorSession).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedCaseVersion\":2,\"sourceRevision\":1}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertEquals("CONFIRMED", json.readTree(confirmed).get("submissionStatus").asString());
        String list = mvc.perform(get("/api/v1/cases").session(authorSession))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertTrue(list.contains(caseId.toString()), "the author must see the case in the list");
    }

    @Test
    void revokedMembershipClosesAccessImmediately() throws Exception {
        MockHttpSession session = new MockHttpSession();
        UUID author = login(session, AUTHOR_INIT_DATA);
        UUID house = UUID.randomUUID();
        jdbc.update("INSERT INTO house(id, address) VALUES (?, 'Дом с отзывом')", house);
        jdbc.update("INSERT INTO membership VALUES (?,?,'RESIDENT')", author, house);
        String created = mvc.perform(post("/api/v1/cases").session(session).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"houseId\":\"" + house + "\",\"sourceText\":\"Течёт кран\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        UUID caseId = UUID.fromString(json.readTree(created).get("caseId").asString());
        mvc.perform(get("/api/v1/cases/" + caseId).session(session)).andExpect(status().isOk());
        // Revocation keeps the row: service_case still references it.
        jdbc.update("UPDATE membership SET revoked_at = now() WHERE user_id = ? AND house_id = ?", author, house);
        // The same live session must lose access without a new login.
        mvc.perform(get("/api/v1/cases/" + caseId).session(session)).andExpect(status().isNotFound());
        String me = mvc.perform(get("/api/v1/me").session(session)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertFalse(me.contains(house.toString()), "a revoked house must disappear from /me");
    }

    @Test
    void replayAfterRevocationDoesNotLeakTheStoredResult() throws Exception {
        MockHttpSession session = new MockHttpSession();
        UUID author = login(session, AUTHOR_INIT_DATA);
        UUID house = UUID.randomUUID();
        jdbc.update("INSERT INTO house(id, address) VALUES (?, 'Дом replay')", house);
        jdbc.update("INSERT INTO membership VALUES (?,?,'RESIDENT')", author, house);
        String key = UUID.randomUUID().toString();
        String body = "{\"houseId\":\"" + house + "\",\"sourceText\":\"Не работает домофон\"}";
        mvc.perform(post("/api/v1/cases").session(session).with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());
        jdbc.update("UPDATE membership SET revoked_at = now() WHERE user_id = ? AND house_id = ?", author, house);
        // The stored response must not be replayed to someone who lost access.
        mvc.perform(post("/api/v1/cases").session(session).with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());
    }

    @Test
    void bodyFieldsCannotGrantAccessOrRole() throws Exception {
        MockHttpSession session = new MockHttpSession();
        UUID author = login(session, AUTHOR_INIT_DATA);
        UUID foreignHouse = UUID.randomUUID();
        jdbc.update("INSERT INTO house(id, address) VALUES (?, 'Чужой дом')", foreignHouse);
        // A house the actor has no membership in is refused even though the body names it.
        mvc.perform(post("/api/v1/cases").session(session).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"houseId\":\"" + foreignHouse + "\",\"sourceText\":\"Чужой дом\"}"))
            .andExpect(status().isForbidden());
        // Unknown body fields such as role or authorId must not be honoured.
        UUID ownHouse = UUID.randomUUID();
        jdbc.update("INSERT INTO house(id, address) VALUES (?, 'Свой дом')", ownHouse);
        jdbc.update("INSERT INTO membership VALUES (?,?,'RESIDENT')", author, ownHouse);
        String created = mvc.perform(post("/api/v1/cases").session(session).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"houseId\":\"" + ownHouse + "\",\"sourceText\":\"Свой дом\","
                    + "\"authorId\":\"" + UUID.randomUUID() + "\",\"role\":\"DISPATCHER\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        assertEquals(author.toString(), json.readTree(created).get("authorId").asString(),
            "the author must come from the session, never from the body");
        assertEquals(0, (int) jdbc.queryForObject(
            "SELECT count(*) FROM membership WHERE user_id = ? AND role = 'DISPATCHER'",
            Integer.class, author), "a body field must not grant a dispatcher role");
    }
    @ParameterizedTest(name = "revoked author cannot {0}, replay={1}")
    @CsvSource({"source,false", "items,false", "confirm,false", "source,true", "items,true", "confirm,true"})
    void revocationBlocksDraftCommandsAndTheirSavedResponses(String operation, boolean replay) throws Exception {
        MockHttpSession session = new MockHttpSession();
        UUID author = login(session, AUTHOR_INIT_DATA);
        UUID house = UUID.randomUUID();
        jdbc.update("INSERT INTO house(id, address) VALUES (?, 'Проверка отзыва доступа')", house);
        jdbc.update("INSERT INTO membership VALUES (?,?,'RESIDENT')", author, house);
        String created = mvc.perform(post("/api/v1/cases").session(session).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("houseId", house, "sourceText", "Не горит свет"))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID caseId = UUID.fromString(json.readTree(created).get("caseId").asString());
        String items = "[{\"category\":\"LIGHTING\",\"location\":\"Этаж 3\",\"symptom\":\"Темно\"}]";
        mvc.perform(patch("/api/v1/cases/" + caseId + "/items").session(session).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedCaseVersion\":1,\"items\":" + items + "}"))
            .andExpect(status().isOk());
        String body = switch (operation) {
            case "source" -> "{\"expectedCaseVersion\":2,\"sourceText\":\"Свет не работает второй день\"}";
            case "items" -> "{\"expectedCaseVersion\":2,\"items\":" + items + "}";
            case "confirm" -> "{\"expectedCaseVersion\":2,\"sourceRevision\":1}";
            default -> throw new IllegalArgumentException(operation);
        };
        String key = UUID.randomUUID().toString();
        if (replay) draftCommand(session, caseId, operation, key, body).andExpect(status().isOk());
        long version = jdbc.queryForObject("SELECT version FROM service_case WHERE id = ?", Long.class, caseId);
        long events = jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE case_id = ?", Long.class, caseId);
        long commands = jdbc.queryForObject("SELECT count(*) FROM processed_command WHERE actor_id = ?", Long.class, author);
        jdbc.update("UPDATE membership SET revoked_at = now() WHERE user_id = ? AND house_id = ?", author, house);
        String rejected = draftCommand(session, caseId, operation, key, body)
            .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertEquals("RESOURCE_NOT_FOUND", json.readTree(rejected).get("code").asString());
        var problem = json.readTree(rejected);
        assertFalse(problem.has("caseId") || problem.has("sourceText") || problem.has("items"),
            "refusal must contain only the problem, not the stored case projection");
        assertEquals(version, jdbc.queryForObject("SELECT version FROM service_case WHERE id = ?", Long.class, caseId));
        assertEquals(events, jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE case_id = ?", Long.class, caseId));
        assertEquals(commands, jdbc.queryForObject("SELECT count(*) FROM processed_command WHERE actor_id = ?", Long.class, author));
    }

    private org.springframework.test.web.servlet.ResultActions draftCommand(MockHttpSession session, UUID caseId,
            String operation, String key, String body) throws Exception {
        String path = "/api/v1/cases/" + caseId + "/" + operation;
        MockHttpServletRequestBuilder request = operation.equals("confirm") ? post(path) : patch(path);
        return mvc.perform(request.session(session).with(csrf()).header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

}
