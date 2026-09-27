package ru.koren.config;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S-07 / QA-R09 over a real port, not MockMvc: MockMvc neither forwards failures to /error nor masks the
 * CSRF token, so both defects it was hiding - every 400/404/500 turning into 401, and an untested CSRF
 * handshake - are only visible here. Every response carries X-Request-Id, and a failure is logged
 * under that id without its message.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=${DB_TEST_URL}", "spring.datasource.username=${DB_TEST_USER:koren}",
    "spring.datasource.password=${DB_TEST_PASSWORD:koren_local_only}",
    "koren.max.bot-token=k1-test-bot-token:not-a-real-secret"
})
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
@ExtendWith(OutputCaptureExtension.class)
class ErrorResponsesHttpTest {

    private static final String AUTHOR_INIT_DATA =
        "user=%7B%22id%22%3A424242%2C%22first_name%22%3A%22Max%22%2C%22last_name%22%3A%22User%22"
            + "%2C%22username%22%3Anull%2C%22language_code%22%3A%22ru%22%7D&ip=192.168.0.1"
            + "&auth_date=1800000000&query_id=AAE1YmYAAAAANTmv4t3W0h01"
            + "&hash=fa2ff9a4255c0e16b0e5be5a4bc6bded45aadad37b589cebb251fc95d7080ad4";
    private static final String HIDDEN_DETAIL = "internal-detail-that-must-not-leak";

    /** A route that fails the way an unforeseen bug would. Under /items/** so security lets it through. */
    @RestController
    static class FailingController {
        @GetMapping("/api/v1/items/test-failure/boom")
        String boom() {
            throw new IllegalStateException(HIDDEN_DETAIL);
        }
    }

    @TestConfiguration
    @Import(FailingController.class)
    static class Config {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.ofEpochSecond(1_800_000_000L), ZoneOffset.UTC);
        }
    }

    @Value("${local.server.port}")
    int port;

    private final JsonMapper json = JsonMapper.builder().build();

    private record Answer(int status, String contentType, String requestId, String body) {
        JsonNode json(JsonMapper mapper) {
            return mapper.readTree(body);
        }
    }

    /** One browser: its own cookie jar, so the session and the XSRF-TOKEN cookie travel like in MAX. */
    private final class Browser {
        final HttpClient client = HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrfHeader;
        String csrfToken;

        Answer send(String method, String path, String body) throws Exception {
            return send(method, path, body, Map.of());
        }

        Answer send(String method, String path, String body, Map<String, String> headers) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
                .header("Accept", "application/json");
            if (body != null) request.header("Content-Type", "application/json");
            headers.forEach(request::header);
            if (csrfToken != null && !"GET".equals(method)) request.header(csrfHeader, csrfToken);
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Answer(response.statusCode(),
                response.headers().firstValue("Content-Type").orElse(""),
                response.headers().firstValue("X-Request-Id").orElse(null), response.body());
        }

        /** What the Mini App does: fetch the masked token, then send it back as a header. */
        void fetchCsrf() throws Exception {
            var answer = send("GET", "/api/v1/csrf", null);
            assertEquals(200, answer.status());
            var token = answer.json(json);
            csrfHeader = token.get("headerName").asString();
            csrfToken = token.get("token").asString();
        }

        void login() throws Exception {
            fetchCsrf();
            var answer = send("POST", "/api/v1/auth/max", "{\"initData\":\"" + AUTHOR_INIT_DATA + "\"}");
            assertEquals(200, answer.status(), answer.body());
        }
    }

    private void assertProblem(Answer answer, int status, String code) {
        assertEquals(status, answer.status(), answer.body());
        assertTrue(answer.contentType().startsWith("application/problem+json"),
            "an API error is problem+json, never an HTML page: " + answer.contentType());
        assertEquals(code, answer.json(json).get("code").asString());
        // Every refusal, including 401/403/411/413 written before any controller, names its request in the body.
        assertNotNull(answer.json(json).get("requestId"), "no requestId in " + answer.body());
        assertEquals(answer.requestId(), answer.json(json).get("requestId").asString());
    }

    @Test
    void theRealCsrfHandshakeLogsInAndAMissingTokenIsNamedAsSuch() throws Exception {
        var withoutToken = new Browser();
        var refused = withoutToken.send("POST", "/api/v1/auth/max",
            "{\"initData\":\"" + AUTHOR_INIT_DATA + "\"}");
        assertProblem(refused, 403, "CSRF_TOKEN_INVALID");

        var browser = new Browser();
        browser.login();
        assertEquals(200, browser.send("GET", "/api/v1/me", null).status(), "the session survives the login");
    }

    @Test
    void aMalformedIdIsABadRequestNotALoginPrompt() throws Exception {
        var browser = new Browser();
        browser.login();
        var answer = browser.send("GET", "/api/v1/cases/not-a-uuid", null);
        assertProblem(answer, 400, "BAD_REQUEST");
        assertFalse(answer.body().contains("not-a-uuid"), "the input is not echoed back");
    }

    @Test
    void aRouteThisApiDoesNotHaveIsNotFoundForASignedInUser() throws Exception {
        var browser = new Browser();
        browser.login();
        assertProblem(browser.send("GET", "/api/v1/no-such-route", null), 404, "NOT_FOUND");
    }

    @Test
    void withoutASessionEveryClosedRouteStillAsksForLogin() throws Exception {
        // Closed by default: an anonymous caller learns nothing about which routes exist.
        var anonymous = new Browser();
        assertProblem(anonymous.send("GET", "/api/v1/no-such-route", null), 401, "AUTH_REQUIRED");
        assertProblem(anonymous.send("GET", "/api/v1/cases", null), 401, "AUTH_REQUIRED");
    }

    @Test
    void anUnexpectedFailureIsA500WithoutItsDetails(CapturedOutput output) throws Exception {
        var browser = new Browser();
        browser.login();
        var answer = browser.send("GET", "/api/v1/items/test-failure/boom", null);
        assertProblem(answer, 500, "INTERNAL_ERROR");
        assertFalse(answer.body().contains(HIDDEN_DETAIL), "the exception message stays on the server");
        assertFalse(answer.body().contains("IllegalStateException"), "so does the exception class");

        // The user can quote one id, and the log finds the failure under it.
        assertNotNull(answer.requestId());
        assertEquals(answer.requestId(), answer.json(json).get("requestId").asString());
        String log = output.getAll();
        assertTrue(log.contains("[" + answer.requestId() + "]"), "the failure is logged under the request id");
        assertTrue(log.contains("java.lang.IllegalStateException"), "the class and frames are logged");
        assertFalse(log.contains(HIDDEN_DETAIL), "the message is not: it may hold user text or SQL values");
    }

    @Test
    void everyAnswerCarriesARequestIdAndOnlyASafeOneIsKept() throws Exception {
        var browser = new Browser();
        browser.login();
        var ok = browser.send("GET", "/api/v1/me", null);
        assertEquals(200, ok.status());
        assertTrue(ok.requestId().matches("[0-9a-f-]{36}"), "a fresh id when the caller sent none");

        var quoted = browser.send("GET", "/api/v1/me", null, Map.of("X-Request-Id", "qa-r09-check-0001"));
        assertEquals("qa-r09-check-0001", quoted.requestId(), "a tester's own id is kept to find it in the log");

        String forged = "x".repeat(200);
        var refused = browser.send("GET", "/api/v1/me", null, Map.of("X-Request-Id", forged));
        assertNotEquals(forged, refused.requestId(), "an id that could flood a log line is replaced");
        assertProblem(new Browser().send("GET", "/api/v1/me", null), 401, "AUTH_REQUIRED");
        assertNotNull(new Browser().send("GET", "/api/v1/me", null).requestId(), "a refusal has an id too");
    }

    @Test
    void theErrorPageCannotBeOpenedDirectly() throws Exception {
        var browser = new Browser();
        browser.login();
        // Only a forward after a failure may reach /error; asking for it is asking for a missing route.
        assertProblem(browser.send("GET", "/error", null), 404, "NOT_FOUND");
    }

    @Test
    void anOversizedBodyIsRefusedBeforeItIsRead() throws Exception {
        var browser = new Browser();
        browser.login();
        String huge = "{\"houseId\":\"" + java.util.UUID.randomUUID() + "\",\"sourceText\":\""
            + "я".repeat(200_000) + "\"}";
        var answer = browser.send("POST", "/api/v1/cases", huge,
            Map.of("Idempotency-Key", java.util.UUID.randomUUID().toString()));
        assertProblem(answer, 413, "PAYLOAD_TOO_LARGE");
        assertNotNull(answer.requestId(), "a refusal still carries its request id");
    }

    @Test
    void aBodyOfUnknownLengthIsRefused() throws Exception {
        var browser = new Browser();
        browser.login();
        // ofInputStream has no length, so the client sends it chunked.
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/cases"))
            .POST(HttpRequest.BodyPublishers.ofInputStream(
                () -> new java.io.ByteArrayInputStream("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8))))
            .header("Content-Type", "application/json")
            .header(browser.csrfHeader, browser.csrfToken)
            .build();
        var response = browser.client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(411, response.statusCode(), response.body());
        assertEquals("LENGTH_REQUIRED", json.readTree(response.body()).get("code").asString());
    }

    @Test
    void aPageSizeOutsideTheLimitIsRefusedNotTruncated() throws Exception {
        var browser = new Browser();
        browser.login();
        assertEquals(422, browser.send("GET", "/api/v1/cases?limit=10000", null).status(),
            "a huge page is refused outright, never silently cut");
        assertEquals(422, browser.send("GET", "/api/v1/cases?limit=0", null).status());
        assertProblem(browser.send("GET", "/api/v1/cases?limit=abc", null), 400, "BAD_REQUEST");
    }
}
