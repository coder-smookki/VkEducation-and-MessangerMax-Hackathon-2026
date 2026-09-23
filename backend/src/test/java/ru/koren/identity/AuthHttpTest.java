package ru.koren.identity;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Login over real HTTP and PostgreSQL. The signed vector and the fixed clock come from
 * MaxInitDataValidatorTest, whose constants were produced by an independent python tool.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=${DB_TEST_URL}", "spring.datasource.username=${DB_TEST_USER:koren}",
    "spring.datasource.password=${DB_TEST_PASSWORD:koren_local_only}",
    "koren.max.bot-token=k1-test-bot-token:not-a-real-secret"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class AuthHttpTest {

    private static final String VALID_INIT_DATA =
        "user=%7B%22id%22%3A424242%2C%22first_name%22%3A%22Max%22%2C%22last_name%22%3A%22User%22"
            + "%2C%22username%22%3Anull%2C%22language_code%22%3A%22ru%22%7D&ip=192.168.0.1"
            + "&auth_date=1800000000&query_id=AAE1YmYAAAAANTmv4t3W0h01"
            + "&hash=fa2ff9a4255c0e16b0e5be5a4bc6bded45aadad37b589cebb251fc95d7080ad4";

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.ofEpochSecond(1_800_000_000L), ZoneOffset.UTC);
        }
    }

    @Autowired MockMvc mvc;

    private final JsonMapper json = JsonMapper.builder().build();

    private String body(String initData) {
        return "{\"initData\":\"" + initData + "\"}";
    }

    @Test
    void loginCreatesSessionAndMeReturnsTheSameInternalId() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String loginBody = mvc.perform(post("/api/v1/auth/max").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(VALID_INIT_DATA)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JsonNode login = json.readTree(loginBody);
        String meBody = mvc.perform(get("/api/v1/me").session(session))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertEquals(login.get("userId").asString(), json.readTree(meBody).get("userId").asString(),
            "the next request must resolve the same internal user id");
        assertTrue(json.readTree(meBody).get("memberships").isArray());
    }

    @Test
    void tamperedSignatureIsRejectedWithoutSession() throws Exception {
        String tampered = VALID_INIT_DATA.substring(0, VALID_INIT_DATA.length() - 1) + "0";
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/max").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(tampered)))
            .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void meWithoutSessionIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutInvalidatesTheSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/max").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(VALID_INIT_DATA)))
            .andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/logout").session(session).with(csrf()))
            .andExpect(status().isNoContent());
        assertTrue(session.isInvalid(), "logout must invalidate the server session");
    }
}
