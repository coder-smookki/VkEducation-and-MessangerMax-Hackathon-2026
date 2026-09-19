package ru.koren.identity;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real disposable PostgreSQL, no H2. Not transactional: the duplicate-key retry
 * path is only reachable in auto-commit mode, and the race test needs independent
 * connections. Synthetic max_user_id values are deleted after each test.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${DB_TEST_URL}",
        "spring.datasource.username=${DB_TEST_USER:koren}",
        "spring.datasource.password=${DB_TEST_PASSWORD:koren_local_only}"
})
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class MaxUserProvisionerTest {

    private static final AtomicLong SEQUENCE = new AtomicLong(9_000_000_000_000_000_000L);

    @Autowired JdbcTemplate jdbc;
    private MaxUserProvisioner provisioner;
    private final List<Long> usedMaxIds = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setup() {
        provisioner = new MaxUserProvisioner(jdbc);
    }

    @AfterEach
    void cleanup() {
        for (long maxId : usedMaxIds) {
            jdbc.update("DELETE FROM app_user WHERE max_user_id = ?", maxId);
        }
    }

    @Test
    void createsUserForNewMaxId() {
        long maxId = nextMaxId();
        UUID id = provisioner.findOrCreate(maxId);
        assertNotNull(id);
        assertEquals(1, countByMaxId(maxId));
        assertEquals(id, idByMaxId(maxId));
    }

    @Test
    void repeatLoginReturnsSameId() {
        long maxId = nextMaxId();
        UUID first = provisioner.findOrCreate(maxId);
        UUID second = provisioner.findOrCreate(maxId);
        assertEquals(first, second);
        assertEquals(1, countByMaxId(maxId));
    }

    @Test
    void concurrentLoginsYieldSingleUser() throws Exception {
        for (int round = 0; round < 8; round++) {
            long maxId = nextMaxId();
            CyclicBarrier start = new CyclicBarrier(2);
            ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                var call = (java.util.concurrent.Callable<UUID>) () -> {
                    start.await(10, TimeUnit.SECONDS);
                    return provisioner.findOrCreate(maxId);
                };
                Future<UUID> a = pool.submit(call);
                Future<UUID> b = pool.submit(call);
                UUID idA = await(a, errors);
                UUID idB = await(b, errors);
                assertTrue(errors.isEmpty(), "concurrent login failed: " + errors);
                assertEquals(idA, idB);
                assertEquals(1, countByMaxId(maxId));
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private UUID await(Future<UUID> future, ConcurrentLinkedQueue<Throwable> errors) {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            errors.add(e);
            return null;
        }
    }

    private long nextMaxId() {
        long maxId = SEQUENCE.incrementAndGet();
        usedMaxIds.add(maxId);
        return maxId;
    }

    private int countByMaxId(long maxId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM app_user WHERE max_user_id = ?", Integer.class, maxId);
        return count == null ? 0 : count;
    }

    private UUID idByMaxId(long maxId) {
        return jdbc.queryForObject("SELECT id FROM app_user WHERE max_user_id = ?", UUID.class, maxId);
    }
}
