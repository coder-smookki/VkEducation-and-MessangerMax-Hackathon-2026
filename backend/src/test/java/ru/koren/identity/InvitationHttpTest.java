package ru.koren.identity;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import tools.jackson.databind.json.JsonMapper;
import ru.koren.identity.application.InvitationService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** One-time invitation codes over HTTP, including two users racing for the same code. */
@SpringBootTest(properties = {
    "spring.datasource.url=${DB_TEST_URL}", "spring.datasource.username=${DB_TEST_USER:koren}",
    "spring.datasource.password=${DB_TEST_PASSWORD:koren_local_only}",
    "koren.max.bot-token=k1-test-bot-token:not-a-real-secret"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class InvitationHttpTest {

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

    /** Fixed codes would collide with rows left by an earlier run: the hash is unique in the table. */
    private String uniqueCode(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private UUID login(MockHttpSession session, String initData) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/max").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"initData\":\"" + initData + "\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("userId").asString());
    }

    private UUID invitation(String code, String interval) {
        UUID house = UUID.randomUUID();
        jdbc.update("INSERT INTO house(id, address) VALUES (?, 'Дом приглашения')", house);
        jdbc.update("""
                INSERT INTO house_invitation(id, house_id, code_hash, expires_at)
                VALUES (gen_random_uuid(), ?, ?, now() + CAST(? AS interval))
                """, house, InvitationService.hash(code), interval);
        return house;
    }

    private int claim(MockHttpSession session, String code) throws Exception {
        return mvc.perform(post("/api/v1/memberships/claim").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"invitationCode\":\"" + code + "\"}"))
            .andReturn().getResponse().getStatus();
    }

    @Test
    void validCodeGrantsResidentAndCannotBeUsedTwice() throws Exception {
        MockHttpSession session = new MockHttpSession();
        login(session, AUTHOR_INIT_DATA);
        String code = uniqueCode("KOREN-ONE-SHOT");
        UUID house = invitation(code, "7 days");
        assertEquals(200, claim(session, code));
        assertEquals("RESIDENT", jdbc.queryForObject(
            "SELECT role FROM membership WHERE house_id = ?", String.class, house));
        // The same code must not work again, not even for the same user.
        assertEquals(403, claim(session, code));
    }

    @Test
    void expiredCodeIsRejectedAndReasonIsNotDisclosed() throws Exception {
        MockHttpSession session = new MockHttpSession();
        login(session, AUTHOR_INIT_DATA);
        String code = uniqueCode("KOREN-EXPIRED");
        invitation(code, "-1 hour");
        assertEquals(403, claim(session, code));
        // An unknown code answers exactly like an expired one: guessing gets no hint.
        assertEquals(403, claim(session, uniqueCode("KOREN-NEVER-EXISTED")));
    }

    @Test
    void twoUsersRacingForOneCodeProduceExactlyOneMembership() throws Exception {
        MockHttpSession first = new MockHttpSession();
        MockHttpSession second = new MockHttpSession();
        login(first, AUTHOR_INIT_DATA);
        login(second, STRANGER_INIT_DATA);
        String code = uniqueCode("KOREN-RACE");
        UUID house = invitation(code, "7 days");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Callable<Integer>> attempts = List.of(
            () -> claim(first, code), () -> claim(second, code));
        List<Future<Integer>> results = pool.invokeAll(attempts);
        pool.shutdown();
        int granted = 0;
        for (Future<Integer> result : results) {
            if (result.get() == 200) granted++;
        }
        assertEquals(1, granted, "a one-time code must be consumed by exactly one request");
        assertEquals(1, (int) jdbc.queryForObject(
            "SELECT count(*) FROM membership WHERE house_id = ?", Integer.class, house));
    }
}
