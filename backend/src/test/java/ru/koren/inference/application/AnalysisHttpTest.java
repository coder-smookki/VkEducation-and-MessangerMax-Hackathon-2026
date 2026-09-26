package ru.koren.inference.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
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
import ru.koren.inference.InferenceClient;
import ru.koren.inference.InferenceContract.Evidence;
import ru.koren.inference.InferenceContract.Mode;
import ru.koren.inference.InferenceContract.Reason;
import ru.koren.inference.InferenceContract.Relation;
import ru.koren.inference.InferenceContract.Request;
import ru.koren.inference.InferenceContract.Response;
import ru.koren.inference.InferenceContract.Suggestion;
import ru.koren.inference.SuggestionValidator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S-06: a saved report queues its analysis, the worker asks Python outside any transaction, and the
 * suggestions route serves a result only while it still describes the case. The schedule is pushed an
 * hour away so the worker is driven by hand; Python is replaced, but its answers still pass the validator.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=${DB_TEST_URL}", "spring.datasource.username=${DB_TEST_USER:koren}",
    "spring.datasource.password=${DB_TEST_PASSWORD:koren_local_only}",
    "koren.max.bot-token=k1-test-bot-token:not-a-real-secret",
    "koren.analysis.enabled=true",
    "koren.analysis.poll-ms=3600000"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class AnalysisHttpTest {

    private static final String AUTHOR_INIT_DATA =
        "user=%7B%22id%22%3A424242%2C%22first_name%22%3A%22Max%22%2C%22last_name%22%3A%22User%22"
            + "%2C%22username%22%3Anull%2C%22language_code%22%3A%22ru%22%7D&ip=192.168.0.1"
            + "&auth_date=1800000000&query_id=AAE1YmYAAAAANTmv4t3W0h01"
            + "&hash=fa2ff9a4255c0e16b0e5be5a4bc6bded45aadad37b589cebb251fc95d7080ad4";
    private static final String DISPATCHER_INIT_DATA =
        "start_param=ref%3Da%3Db&auth_date=1800000000"
            + "&user=%7B%22id%22%3A31337%2C%22first_name%22%3A%22Eq%22%2C%22last_name%22%3Anull%7D"
            + "&hash=b27c3f005d80d8ccfcca2d27e0dc2d5c33f0be1ee3526adf0945cc854d11cc98";

    /** Stands in for Python. RULES claims the first item with the whole text as evidence. */
    static class FakeInference extends InferenceClient {
        volatile String behaviour = "RULES";
        final AtomicInteger calls = new AtomicInteger();

        FakeInference() {
            super("http://127.0.0.1:9");
        }

        @Override
        public Optional<Response> tryCompare(Request request) {
            calls.incrementAndGet();
            if ("DOWN".equals(behaviour)) return Optional.empty();
            if ("THROW".equals(behaviour)) throw new IllegalStateException("unexpected");
            boolean stub = "STUB".equals(behaviour);
            var suggestions = new ArrayList<Suggestion>();
            for (int index = 0; index < request.items().size(); index++) {
                var item = request.items().get(index);
                String text = request.reportText();
                boolean claim = !stub && index == 0;
                suggestions.add(new Suggestion(item.itemId(), item.contentVersion(), item.workRound(),
                    claim ? Relation.REPORTED_DONE : Relation.INSUFFICIENT_INFORMATION,
                    claim ? new Evidence(0, text.codePointCount(0, text.length()), text) : null,
                    stub ? Reason.MODEL_NOT_CONFIGURED : Reason.ANALYZED));
            }
            return Optional.of(SuggestionValidator.validate(request, new Response(request.caseId(),
                request.sourceRevision(), request.reportId(), stub ? "stub-v1" : "rules-test",
                stub ? Mode.STUB : Mode.RULES, suggestions)));
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.ofEpochSecond(1_800_000_000L), ZoneOffset.UTC);
        }

        @Bean
        @Primary
        FakeInference fakeInference() {
            return new FakeInference();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AnalysisWorker worker;
    @Autowired FakeInference inference;

    private final JsonMapper json = JsonMapper.builder().build();
    private MockHttpSession authorSession;
    private MockHttpSession dispatcherSession;
    private UUID dispatcher;
    private UUID house;

    @BeforeEach
    void freshHouse() throws Exception {
        inference.behaviour = "RULES";
        inference.calls.set(0);
        // Only this test's analysis may be picked up by the worker.
        jdbc.update("UPDATE report_analysis SET status = 'MANUAL_REQUIRED' WHERE status = 'QUEUED'");
        authorSession = new MockHttpSession();
        UUID author = login(authorSession, AUTHOR_INIT_DATA);
        dispatcherSession = new MockHttpSession();
        dispatcher = login(dispatcherSession, DISPATCHER_INIT_DATA);
        house = UUID.randomUUID();
        jdbc.update("INSERT INTO house(id, address) VALUES (?, ?)", house, "Analysis " + house);
        jdbc.update("INSERT INTO membership VALUES (?,?,'RESIDENT'), (?,?,'DISPATCHER')",
            author, house, dispatcher, house);
    }

    private UUID login(MockHttpSession session, String initData) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/max").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"initData\":\"" + initData + "\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("userId").asString());
    }

    private JsonNode send(org.springframework.test.web.servlet.RequestBuilder request, int expected)
        throws Exception {
        String body = mvc.perform(request).andExpect(status().is(expected))
            .andReturn().getResponse().getContentAsString();
        return body.isEmpty() ? null : json.readTree(body);
    }

    private JsonNode confirmedCase() throws Exception {
        JsonNode created = send(post("/api/v1/cases").session(authorSession).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"houseId\":\"" + house + "\",\"sourceText\":\"Темно на третьем этаже, дверь не закрывается.\"}"),
            201);
        String caseId = created.get("caseId").asString();
        send(patch("/api/v1/cases/" + caseId + "/items").session(authorSession).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedCaseVersion\":1,\"items\":[{\"itemId\":null,\"category\":\"LIGHTING\","
                    + "\"location\":\"Этаж 3\",\"symptom\":\"Не горит свет\"},{\"itemId\":null,"
                    + "\"category\":\"ENTRANCE_DOOR\",\"location\":\"Вход\",\"symptom\":\"Дверь не закрывается\"}]}"),
            200);
        return send(post("/api/v1/cases/" + caseId + "/confirm").session(authorSession).with(csrf())
            .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedCaseVersion\":2,\"sourceRevision\":1}"), 200);
    }

    private List<String> itemIds(JsonNode view) {
        var ids = new ArrayList<String>();
        view.get("items").forEach(item -> ids.add(item.get("itemId").asString()));
        return ids;
    }

    /** Saves a report over both items and returns the creation response. */
    private JsonNode report(JsonNode view) throws Exception {
        List<String> ids = itemIds(view);
        return send(post("/api/v1/cases/" + view.get("caseId").asString() + "/reports")
            .session(dispatcherSession).with(csrf())
            .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedCaseVersion\":" + view.get("caseVersion").asLong()
                + ",\"reportText\":\"Лампу на третьем этаже заменили.\",\"targetItemIds\":[\""
                + ids.get(0) + "\",\"" + ids.get(1) + "\"]}"), 201);
    }

    private JsonNode suggestions(MockHttpSession session, String reportId) throws Exception {
        return send(get("/api/v1/reports/" + reportId + "/suggestions").session(session), 200);
    }

    @Test
    void savingAReportQueuesItsAnalysisAndSaysSo() throws Exception {
        JsonNode saved = report(confirmedCase());
        assertEquals("QUEUED", saved.get("analysis").get("status").asString());

        JsonNode view = suggestions(dispatcherSession, saved.get("report").get("reportId").asString());
        assertEquals("QUEUED", view.get("status").asString());
        assertFalse(view.get("analysisId").isNull());
        assertTrue(view.get("result").isNull(), "nothing to show before Python has answered");
        assertEquals(0, inference.calls.get(), "saving a report must not call Python");
    }

    @Test
    void aRulesAnswerIsServedWithEveryTargetItem() throws Exception {
        JsonNode caseView = confirmedCase();
        String reportId = report(caseView).get("report").get("reportId").asString();
        worker.drainOnce();

        JsonNode view = suggestions(authorSession, reportId);
        assertEquals("READY", view.get("status").asString());
        JsonNode result = view.get("result");
        assertEquals("RULES", result.get("mode").asString());
        assertEquals(2, result.get("suggestions").size(), "the door stays in the list even if unmentioned");
        assertEquals("REPORTED_DONE", result.get("suggestions").get(0).get("relation").asString());
    }

    @Test
    void aStubAnswerIsNeverShownAsAnalysis() throws Exception {
        inference.behaviour = "STUB";
        String reportId = report(confirmedCase()).get("report").get("reportId").asString();
        worker.drainOnce();

        JsonNode view = suggestions(dispatcherSession, reportId);
        assertEquals("MANUAL_REQUIRED", view.get("status").asString());
        assertTrue(view.get("result").isNull(), "a well-formed STUB still says nothing");
    }

    @Test
    void pythonBeingDownIsRetriedAndThenAdmittedAsManualWork() throws Exception {
        inference.behaviour = "DOWN";
        String reportId = report(confirmedCase()).get("report").get("reportId").asString();

        assertEquals(1, worker.drainOnce().retried());
        assertEquals("QUEUED", suggestions(dispatcherSession, reportId).get("status").asString());
        for (int attempt = 0; attempt < AnalysisWorker.MAX_ATTEMPTS; attempt++) {
            jdbc.update("UPDATE report_analysis SET available_at = now() - interval '1 minute', leased_until = NULL"
                + " WHERE report_id = ?::uuid", reportId);
            worker.drainOnce();
        }

        JsonNode view = suggestions(dispatcherSession, reportId);
        assertEquals("MANUAL_REQUIRED", view.get("status").asString(), "never QUEUED forever");
        assertEquals(AnalysisWorker.MAX_ATTEMPTS, inference.calls.get(), "attempts are capped");
    }

    @Test
    void aConfirmedLinkMakesTheHintStaleAndWithholdsIt() throws Exception {
        JsonNode caseView = confirmedCase();
        String caseId = caseView.get("caseId").asString();
        String reportId = report(caseView).get("report").get("reportId").asString();
        worker.drainOnce();
        assertEquals("READY", suggestions(dispatcherSession, reportId).get("status").asString());

        JsonNode current = send(get("/api/v1/cases/" + caseId).session(dispatcherSession), 200);
        JsonNode light = current.get("items").get(0);
        send(post("/api/v1/reports/" + reportId + "/links").session(dispatcherSession).with(csrf())
            .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedCaseVersion\":" + current.get("caseVersion").asLong() + ",\"links\":[{\"itemId\":\""
                + light.get("itemId").asString() + "\",\"expectedItemVersion\":" + light.get("itemVersion").asLong()
                + ",\"contentVersion\":" + light.get("contentVersion").asInt()
                + ",\"expectedWorkRound\":" + light.get("workRound").asInt()
                + ",\"workStatus\":\"REPORTED_DONE\"}]}"), 200);

        JsonNode view = suggestions(dispatcherSession, reportId);
        assertEquals("STALE", view.get("status").asString(), "the light is in a new round now");
        assertTrue(view.get("result").isNull(), "a hint about a finished round is withheld");
    }

    @Test
    void anUnexpectedFailureIsRetriedAndDoesNotAbortTheBatch() throws Exception {
        inference.behaviour = "THROW";
        String reportId = report(confirmedCase()).get("report").get("reportId").asString();

        var drained = assertDoesNotThrow(() -> worker.drainOnce());
        assertEquals(1, drained.retried());
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
            "SELECT leased_by IS NOT NULL FROM report_analysis WHERE report_id = ?::uuid",
            Boolean.class, reportId), "released for a later attempt, not held until the lease runs out");
    }

    @Test
    void someoneWhoLostAccessGetsNotFound() throws Exception {
        String reportId = report(confirmedCase()).get("report").get("reportId").asString();
        jdbc.update("UPDATE membership SET revoked_at = now() WHERE user_id = ? AND house_id = ?", dispatcher, house);

        send(get("/api/v1/reports/" + reportId + "/suggestions").session(dispatcherSession), 404);
    }
}
