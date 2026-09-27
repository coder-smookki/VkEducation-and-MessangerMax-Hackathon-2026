package ru.koren.identity;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** Real servlet cookies and masked CSRF handshake; real MAX iframe acceptance is a separate check. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=${DB_TEST_URL}", "spring.datasource.username=${DB_TEST_USER:koren}",
    "spring.datasource.password=${DB_TEST_PASSWORD:koren_local_only}",
    "koren.max.bot-token=k1-test-bot-token:not-a-real-secret",
    "server.servlet.session.cookie.secure=true",
    "server.servlet.session.cookie.same-site=None"
})
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class CrossSiteCookieHttpTest {
    // Independent signed fixture shared in meaning with MaxInitDataValidatorTest.
    private static final String INIT_DATA = SignedProfileFixtures.initData("profile");

    @TestConfiguration
    static class Config {
        @Bean @Primary Clock testClock() {
            return Clock.fixed(Instant.ofEpochSecond(1_800_000_000L), ZoneOffset.UTC);
        }
    }

    @Value("${local.server.port}") int port;
    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    }

    private String cookie(HttpResponse<?> response, String name) {
        return response.headers().allValues("Set-Cookie").stream()
            .filter(value -> value.startsWith(name + "=")).findFirst().orElseThrow();
    }

    private HttpResponse<String> login(String csrfCookie, String header, String token) throws Exception {
        // The HTTP hop models nginx -> Java. Copy cookies explicitly: a browser sends Secure
        // cookies only to the public HTTPS endpoint, never directly to this loopback test port.
        var builder = request("/api/v1/auth/max").header("Cookie", csrfCookie.split(";", 2)[0])
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"initData\":\"" + INIT_DATA + "\"}"));
        if (token != null) builder.header(header, token);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void bothCookiesPermitHttpsEmbeddingWhileCsrfStillProtectsLogin() throws Exception {
        var csrf = client.send(request("/api/v1/csrf").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, csrf.statusCode());
        String csrfCookie = cookie(csrf, "XSRF-TOKEN");
        assertTrue(csrfCookie.contains("Secure"));
        assertTrue(csrfCookie.contains("SameSite=None"));
        var token = json.readTree(csrf.body());
        String header = token.get("headerName").asString();

        for (String invalid : new String[] {null, "invalid"}) {
            var refused = login(csrfCookie, header, invalid);
            assertEquals(403, refused.statusCode());
            assertEquals("CSRF_TOKEN_INVALID", json.readTree(refused.body()).get("code").asString());
        }
        var accepted = login(csrfCookie, header, token.get("token").asString());
        assertEquals(200, accepted.statusCode());
        String session = cookie(accepted, "JSESSIONID");
        assertTrue(session.contains("Secure"));
        assertTrue(session.contains("HttpOnly"));
        assertTrue(session.contains("SameSite=None"));
        var me = client.send(request("/api/v1/me").header("Cookie", session.split(";", 2)[0]).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, me.statusCode());
        assertEquals(json.readTree(accepted.body()), json.readTree(me.body()));
        assertEquals("anna_max", json.readTree(me.body()).get("username").asString());
        assertEquals("https://images.example.org/max/avatar.png", json.readTree(me.body()).get("avatarUrl").asString());
    }
}
