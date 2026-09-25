package ru.koren.max;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S-05: the webhook stores a verified update before answering MAX, and answers nothing else.
 * No request here carries a CSRF token or a session: MAX has neither, and that is the point.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=${DB_TEST_URL}", "spring.datasource.username=${DB_TEST_USER:koren}",
    "spring.datasource.password=${DB_TEST_PASSWORD:koren_local_only}",
    "koren.max.bot-token=k1-test-bot-token:not-a-real-secret",
    "koren.max.webhook-secret=k1-test-webhook-secret:not-a-real-secret"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class MaxWebhookHttpTest {

    private static final String SECRET = "k1-test-webhook-secret:not-a-real-secret";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private String update(long updateId) {
        return "{\"update_id\":" + updateId + ",\"message\":{\"text\":\"/start\"}}";
    }

    private int storedFor(String eventKey) {
        return jdbc.queryForObject("SELECT count(*) FROM inbox_event WHERE event_key = ?",
            Integer.class, eventKey);
    }

    private org.springframework.test.web.servlet.ResultActions send(String secret, String body) throws Exception {
        var request = post("/max/webhook").contentType(MediaType.APPLICATION_JSON).content(body);
        if (secret != null) request = request.header(MaxWebhookController.SECRET_HEADER, secret);
        return mvc.perform(request);
    }

    @Test
    void aVerifiedUpdateIsStoredAndAcknowledged() throws Exception {
        long id = Math.abs(UUID.randomUUID().getMostSignificantBits());
        send(SECRET, update(id)).andExpect(status().isOk());
        assertEquals(1, storedFor("update_id:" + id));
    }

    @Test
    void aRedeliveryIsAcknowledgedWithoutASecondRow() throws Exception {
        long id = Math.abs(UUID.randomUUID().getMostSignificantBits());
        send(SECRET, update(id)).andExpect(status().isOk());
        send(SECRET, update(id)).andExpect(status().isOk());
        send(SECRET, update(id)).andExpect(status().isOk());
        assertEquals(1, storedFor("update_id:" + id),
            "MAX retries until it sees success; the inbox must stay single-rowed");
    }

    @Test
    void aWrongSecretStoresNothing() throws Exception {
        long id = Math.abs(UUID.randomUUID().getMostSignificantBits());
        send("k1-test-webhook-secret:not-a-real-secre", update(id)).andExpect(status().isUnauthorized());
        send("", update(id)).andExpect(status().isUnauthorized());
        assertEquals(0, storedFor("update_id:" + id));
    }

    @Test
    void aMissingSecretIsRefused() throws Exception {
        long id = Math.abs(UUID.randomUUID().getMostSignificantBits());
        send(null, update(id)).andExpect(status().isUnauthorized());
        assertEquals(0, storedFor("update_id:" + id));
    }

    @Test
    void anOversizedBodyIsRefused() throws Exception {
        String padding = "x".repeat(70 * 1024);
        send(SECRET, "{\"update_id\":1,\"text\":\"" + padding + "\"}")
            .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void malformedOrNonObjectBodiesAreRefused() throws Exception {
        send(SECRET, "{\"update_id\":").andExpect(status().isBadRequest());
        send(SECRET, "[1,2,3]").andExpect(status().isBadRequest());
        send(SECRET, "\"just a string\"").andExpect(status().isBadRequest());
    }

    @Test
    void anUpdateWithoutAStableIdentifierFallsBackToItsContent() throws Exception {
        String marker = UUID.randomUUID().toString();
        String body = "{\"message\":{\"text\":\"" + marker + "\"}}";
        send(SECRET, body).andExpect(status().isOk());
        send(SECRET, body).andExpect(status().isOk());
        Integer rows = jdbc.queryForObject(
            "SELECT count(*) FROM inbox_event WHERE payload::text LIKE ?", Integer.class, "%" + marker + "%");
        assertEquals(1, rows, "identical bodies without an id must not produce two events");
    }
}
