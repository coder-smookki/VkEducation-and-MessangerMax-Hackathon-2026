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
        assertEquals("Max User", json.readTree(meBody).get("displayName").asString());
        assertTrue(json.readTree(meBody).get("username").isNull());
        assertTrue(json.readTree(meBody).get("avatarUrl").isNull());
    }

    private JsonNode login(MockHttpSession session, String initData) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/auth/max").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(initData)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode me(MockHttpSession session) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/me").session(session))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void verifiedProfileSurvivesFollowingSessionRequests() throws Exception {
        var session = new MockHttpSession();
        JsonNode accepted = login(session, SignedProfileFixtures.initData("profile"));
        assertEquals("Анна Иванова", accepted.get("displayName").asString());
        assertEquals("anna_max", accepted.get("username").asString());
        assertEquals("https://images.example.org/max/avatar.png", accepted.get("avatarUrl").asString());
        assertEquals(accepted, me(session));
        var context = (org.springframework.security.core.context.SecurityContext) session.getAttribute(
            org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertInstanceOf(java.util.UUID.class, context.getAuthentication().getPrincipal());
        assertInstanceOf(MaxProfile.class, context.getAuthentication().getDetails());
    }

    @Test
    void anotherLoginReplacesPreviousProfileAndOldSessionsHaveANullFallback() throws Exception {
        var session = new MockHttpSession();
        JsonNode first = login(session, SignedProfileFixtures.initData("profile"));
        JsonNode second = login(session, SignedProfileFixtures.initData("other_account"));
        assertNotEquals(first.get("userId"), second.get("userId"));
        assertEquals("Другой аккаунт", me(session).get("displayName").asString());
        assertTrue(me(session).get("username").isNull());
        assertTrue(me(session).get("avatarUrl").isNull());

        var context = (org.springframework.security.core.context.SecurityContext) session.getAttribute(
            org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        ((org.springframework.security.authentication.UsernamePasswordAuthenticationToken)
            context.getAuthentication()).setDetails(null);
        JsonNode legacy = me(session);
        assertEquals(second.get("userId"), legacy.get("userId"));
        assertTrue(legacy.get("displayName").isNull());
        assertTrue(legacy.get("username").isNull());
        assertTrue(legacy.get("avatarUrl").isNull());
    }

    @Test
    void logoutClearsProfileBeforeANewAccountSignsIn() throws Exception {
        var firstSession = new MockHttpSession();
        login(firstSession, SignedProfileFixtures.initData("profile"));
        mvc.perform(post("/api/v1/auth/logout").session(firstSession).with(csrf()))
            .andExpect(status().isNoContent());
        assertTrue(firstSession.isInvalid());
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());

        var nextSession = new MockHttpSession();
        login(nextSession, SignedProfileFixtures.initData("other_account"));
        JsonNode nextProfile = me(nextSession);
        assertEquals("Другой аккаунт", nextProfile.get("displayName").asString());
        assertTrue(nextProfile.get("username").isNull());
        assertTrue(nextProfile.get("avatarUrl").isNull());
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

    @Test
    void loginIssuesANewSessionId() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String before = session.getId();

        mvc.perform(post("/api/v1/auth/max").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(VALID_INIT_DATA)))
            .andExpect(status().isOk());
        // A session id known before the login must not stay valid after it.
        assertNotEquals(before, session.getId(), "login must rotate the session id");
    }
}
