package ru.koren.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S-09: a checker can see which commit the stand runs, and nothing else. The SHA comes from the
 * RELEASE_SHA environment variable set at deploy; health stays without details, and the other
 * actuator endpoints stay closed.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=${DB_TEST_URL}", "spring.datasource.username=${DB_TEST_USER:koren}",
    "spring.datasource.password=${DB_TEST_PASSWORD}",
    "koren.max.bot-token=k1-test-bot-token:not-a-real-secret",
    "RELEASE_SHA=0123456789abcdef0123456789abcdef01234567"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class ReleaseInfoHttpTest {

    @Autowired MockMvc mvc;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void theRunningCommitIsPublicAndNothingElseIs() throws Exception {
        String body = mvc.perform(get("/actuator/info")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JsonNode info = json.readTree(body);
        assertEquals("0123456789abcdef0123456789abcdef01234567", info.get("app").get("sha").asString());
        assertEquals(1, info.size(), "only the app block: no environment, build or git details");

        String health = mvc.perform(get("/actuator/health")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertNull(json.readTree(health).get("components"), "health shows no internals");

        for (String closed : new String[]{"/actuator/env", "/actuator/metrics", "/actuator/beans",
            "/actuator/configprops", "/actuator"}) {
            int answer = mvc.perform(get(closed)).andReturn().getResponse().getStatus();
            assertTrue(answer == 401 || answer == 404, closed + " must stay closed, got " + answer);
        }
    }
}
