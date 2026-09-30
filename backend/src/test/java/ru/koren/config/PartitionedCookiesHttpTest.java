package ru.koren.config;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** Encoding/protection proof over a real port and PostgreSQL; TLS/browser policy is tested separately. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=${DB_TEST_URL}", "spring.datasource.username=${DB_TEST_USER:koren}",
    "spring.datasource.password=${DB_TEST_PASSWORD:koren_local_only}",
    "koren.max.bot-token=partitioned-cookie-test-only",
    "server.servlet.session.cookie.secure=true", "server.servlet.session.cookie.same-site=None"
})
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class PartitionedCookiesHttpTest {
    @Value("${local.server.port}") int port;
    final HttpClient client = HttpClient.newHttpClient();
    final JsonMapper json = JsonMapper.builder().build();

    HttpResponse<String> send(String method, String path, String body, Map<String, String> headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    String signedLaunch() throws Exception {
        var fields = new TreeMap<String, String>();
        fields.put("auth_date", Long.toString(Instant.now().getEpochSecond()));
        fields.put("query_id", UUID.randomUUID().toString());
        fields.put("user", "{\"id\":93330002,\"first_name\":\"Cookie test\"}");
        var secretMac = Mac.getInstance("HmacSHA256");
        secretMac.init(new SecretKeySpec("WebAppData".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] secret = secretMac.doFinal("partitioned-cookie-test-only".getBytes(StandardCharsets.UTF_8));
        var signature = Mac.getInstance("HmacSHA256");
        signature.init(new SecretKeySpec(secret, "HmacSHA256"));
        String checked = String.join("\n", fields.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
        fields.put("hash", HexFormat.of().formatHex(signature.doFinal(checked.getBytes(StandardCharsets.UTF_8))));
        return String.join("&", fields.entrySet().stream().map(e -> e.getKey() + "="
            + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8).replace("+", "%20")).toList());
    }

    @Test
    void bothCookiesArePartitionedSecureAndTheSessionStillRequiresCsrfAndSignedMax() throws Exception {
        var csrf = send("GET", "/api/v1/csrf", null, Map.of());
        assertEquals(200, csrf.statusCode());
        String csrfCookie = csrf.headers().allValues("set-cookie").stream()
            .filter(value -> value.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow();
        assertTrue(csrfCookie.contains("Secure"), csrfCookie);
        assertTrue(csrfCookie.contains("SameSite=None"), csrfCookie);
        assertTrue(csrfCookie.contains("Partitioned"), csrfCookie);
        String token = json.readTree(csrf.body()).get("token").asString();
        String cookieHeader = csrfCookie.split(";", 2)[0];
        String body = json.writeValueAsString(Map.of("initData", signedLaunch()));
        // Manual Cookie headers here verify the HTTP encoding; they do not model browser TLS restrictions.
        var missingCsrf = send("POST", "/api/v1/auth/max", body, Map.of("Cookie", cookieHeader));
        assertEquals(403, missingCsrf.statusCode());
        assertEquals("CSRF_TOKEN_INVALID", json.readTree(missingCsrf.body()).get("code").asString());
        var invalidSignature = send("POST", "/api/v1/auth/max", "{\"initData\":\"hash=invalid\"}",
            Map.of("Cookie", cookieHeader, "X-XSRF-TOKEN", token));
        assertEquals(401, invalidSignature.statusCode());
        var login = send("POST", "/api/v1/auth/max", body, Map.of("Cookie", cookieHeader, "X-XSRF-TOKEN", token));
        assertEquals(200, login.statusCode(), login.body());
        String sessionCookie = login.headers().allValues("set-cookie").stream()
            .filter(value -> value.startsWith("JSESSIONID=")).findFirst().orElseThrow();
        assertTrue(sessionCookie.contains("HttpOnly"), sessionCookie);
        assertTrue(sessionCookie.contains("Secure"), sessionCookie);
        assertTrue(sessionCookie.contains("SameSite=None"), sessionCookie);
        assertTrue(sessionCookie.contains("Partitioned"), sessionCookie);
        assertEquals(200, send("GET", "/api/v1/me", null,
            Map.of("Cookie", cookieHeader + "; " + sessionCookie.split(";", 2)[0])).statusCode());
    }
}
