package ru.koren.cases;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.koren.cases.application.CaseException;
import ru.koren.cases.application.CaseService;
import ru.koren.cases.application.CaseView;
import ru.koren.cases.application.DraftItemInput;
import ru.koren.cases.persistence.CaseRepository;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL only; skipped without DB_TEST_URL. Fixture: A/B residents of H1, C resident of H2, D dispatcher of H1. */
@SpringBootTest(properties = {
        "spring.datasource.url=${DB_TEST_URL}", "spring.datasource.username=${DB_TEST_USER:koren}",
        "spring.datasource.password=${DB_TEST_PASSWORD:koren_local_only}"
})
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class CaseServicePostgresTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired CaseService service;

    UUID h1, h2, a, b, c, d;

    @BeforeEach void setup() {
        h1 = UUID.randomUUID(); h2 = UUID.randomUUID();
        a = UUID.randomUUID(); b = UUID.randomUUID(); c = UUID.randomUUID(); d = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            for (UUID user : List.of(a, b, c, d)) jdbc.update("INSERT INTO app_user(id) VALUES (?)", user);
            jdbc.update("INSERT INTO house(id, address) VALUES (?, 'House H1'), (?, 'House H2')", h1, h2);
            jdbc.update("INSERT INTO membership VALUES (?,?,'RESIDENT'), (?,?,'RESIDENT'), (?,?,'RESIDENT'), (?,?,'DISPATCHER')",
                    a, h1, b, h1, c, h2, d, h1);
        });
    }

    @AfterEach void cleanup() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            String casesOfFixture = "SELECT id FROM service_case WHERE house_id IN (?, ?)";
            jdbc.update("DELETE FROM processed_command WHERE actor_id IN (?,?,?,?)", a, b, c, d);
            jdbc.update("DELETE FROM audit_event WHERE case_id IN (" + casesOfFixture + ")", h1, h2);
            jdbc.update("DELETE FROM report_item_link WHERE case_id IN (" + casesOfFixture + ")", h1, h2);
            jdbc.update("DELETE FROM resident_observation WHERE case_id IN (" + casesOfFixture + ")", h1, h2);
            jdbc.update("DELETE FROM work_report WHERE case_id IN (" + casesOfFixture + ")", h1, h2);
            jdbc.update("DELETE FROM item_revision WHERE item_id IN (SELECT id FROM case_item WHERE case_id IN (" + casesOfFixture + "))", h1, h2);
            jdbc.update("DELETE FROM case_item WHERE case_id IN (" + casesOfFixture + ")", h1, h2);
            jdbc.update("DELETE FROM case_revision WHERE case_id IN (" + casesOfFixture + ")", h1, h2);
            jdbc.update("DELETE FROM service_case WHERE house_id IN (?, ?)", h1, h2);
            jdbc.update("DELETE FROM membership WHERE house_id IN (?, ?)", h1, h2);
            jdbc.update("DELETE FROM house WHERE id IN (?, ?)", h1, h2);
            jdbc.update("DELETE FROM app_user WHERE id IN (?,?,?,?)", a, b, c, d);
        });
    }

    private int count(String sql, Object... args) {
        Integer result = jdbc.queryForObject(sql, Integer.class, args);
        return result == null ? 0 : result;
    }

    private static CaseException errorOf(Executable action) {
        return assertThrows(CaseException.class, action);
    }

    private static DraftItemInput item(String category, String location, String symptom) {
        return new DraftItemInput(null, category, location, symptom);
    }

    private CaseView draftWithTwoItems() {
        var created = service.create(a, h1, "На третьем этаже темно, и входная дверь не закрывается.", key());
        return service.patchItems(a, created.caseId(), created.caseVersion(), List.of(
                item("LIGHTING", "Подъезд 1, этаж 3", "Не горит свет"),
                item("ENTRANCE_DOOR", "Подъезд 1, вход", "Дверь не закрывается")), key());
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    @Test void createStoresDraftAtomicallyAndReadsBack() {
        var created = service.create(a, h1, "На третьем этаже темно, и входная дверь не закрывается.", "create-1");
        assertEquals(1, created.caseVersion());
        assertEquals(1, created.sourceRevision());
        assertEquals("DRAFT", created.submissionStatus());
        assertTrue(created.items().isEmpty());

        var withItems = service.patchItems(a, created.caseId(), 1, List.of(
                item("LIGHTING", "Подъезд 1, этаж 3", "Не горит свет"),
                item("ENTRANCE_DOOR", "Подъезд 1, вход", "Дверь не закрывается")), "items-1");
        assertEquals(2, withItems.caseVersion());
        assertEquals(2, withItems.items().size());
        for (var view : withItems.items()) {
            assertEquals(1, view.itemVersion());
            assertEquals(1, view.contentVersion());
            assertEquals(0, view.workRound());
            assertEquals("NOT_ASSIGNED", view.workStatus().name());
            assertEquals("NOT_RECEIVED", view.observation().name());
            assertEquals("ACTIVE", view.administrativeStatus().name());
        }
        assertEquals(2, withItems.progress().total());
        assertFalse(withItems.progress().allConfirmed());

        var readBack = service.getCase(a, created.caseId());
        assertEquals(withItems, readBack);
        assertEquals(1, count("SELECT count(*) FROM service_case WHERE id = ?", created.caseId()));
        assertEquals(1, count("SELECT count(*) FROM case_revision WHERE case_id = ?", created.caseId()));
        assertEquals(2, count("SELECT count(*) FROM case_item WHERE case_id = ?", created.caseId()));
        assertEquals(2, count("SELECT count(*) FROM item_revision WHERE item_id IN (SELECT id FROM case_item WHERE case_id = ?)", created.caseId()));
        assertEquals(2, count("SELECT count(*) FROM audit_event WHERE case_id = ?", created.caseId()));
        assertEquals(2, count("SELECT count(*) FROM processed_command WHERE actor_id = ?", a));
    }

    @Test void patchSourceKeepsPreviousRevision() {
        var created = service.create(a, h1, "Первый текст", "create-2");
        var patched = service.patchSource(a, created.caseId(), 1, "Второй текст", "source-2");
        assertEquals(2, patched.caseVersion());
        assertEquals(2, patched.sourceRevision());
        assertEquals("Второй текст", patched.sourceText());
        assertEquals("Первый текст", jdbc.queryForObject(
                "SELECT source_text FROM case_revision WHERE case_id = ? AND revision = 1", String.class, created.caseId()));
        assertEquals(2, count("SELECT count(*) FROM case_revision WHERE case_id = ?", created.caseId()));
    }

    @Test void patchItemsReplacesDraftListWithoutFictitiousRevisions() {
        var created = service.create(a, h1, "Текст", "create-3");
        var first = service.patchItems(a, created.caseId(), 1,
                List.of(item("LIGHTING", "Этаж 3", "Темно"), item("CLEANING", "Этаж 1", "Грязно")), "items-3a");

        UUID light = itemIdByCategory(first, "LIGHTING");
        UUID cleaning = itemIdByCategory(first, "CLEANING");

        var second = service.patchItems(a, created.caseId(), 2, List.of(
                new DraftItemInput(light, "LIGHTING", "Этаж 3", "Темно"),
                new DraftItemInput(cleaning, "CLEANING", "Этаж 1", "Очень грязно"),
                item("OTHER", "Крыша", "Протекает")), "items-3b");
        var byId = new java.util.HashMap<UUID, ru.koren.cases.application.CaseItemView>();
        second.items().forEach(view -> byId.put(view.itemId(), view));
        // Unchanged content: no new revision, no version bump.
        assertEquals(1, byId.get(light).contentVersion());
        assertEquals(1, byId.get(light).itemVersion());
        // Changed symptom: new revision and contentVersion+1.
        assertEquals(2, byId.get(cleaning).contentVersion());
        assertEquals(2, byId.get(cleaning).itemVersion());
        assertEquals(3, second.items().size());
        assertEquals(1, count("SELECT count(*) FROM item_revision WHERE item_id = ?", light));
        assertEquals(2, count("SELECT count(*) FROM item_revision WHERE item_id = ?", cleaning));

        UUID roof = second.items().stream().filter(view -> !view.itemId().equals(light) && !view.itemId().equals(cleaning))
                .findFirst().orElseThrow().itemId();
        var third = service.patchItems(a, created.caseId(), 3,
                List.of(new DraftItemInput(light, "LIGHTING", "Этаж 3", "Темно")), "items-3c");
        assertEquals(1, third.items().size());
        // Removed draft items leave no rows at all.
        assertEquals(1, count("SELECT count(*) FROM case_item WHERE case_id = ?", created.caseId()));
        assertEquals(0, count("SELECT count(*) FROM case_item WHERE id IN (?, ?)", cleaning, roof));
        assertEquals(0, count("SELECT count(*) FROM item_revision WHERE item_id IN (?, ?)", cleaning, roof));
    }

    @Test void patchItemsRejectsForeignUnknownDuplicateAndOversizedInput() {
        var created = service.create(a, h1, "Текст", "create-4");
        var foreign = service.create(b, h1, "Чужой текст", "create-4b");
        service.patchItems(b, foreign.caseId(), 1, List.of(item("LIGHTING", "Этаж 3", "Темно")), "items-4f");
        UUID foreignItem = jdbc.queryForObject(
                "SELECT id FROM case_item WHERE case_id = ?", UUID.class, foreign.caseId());

        assertEquals("RESOURCE_NOT_FOUND", errorOf(() -> service.patchItems(a, created.caseId(), 1, List.of(
                new DraftItemInput(foreignItem, "LIGHTING", "Этаж 3", "Темно")), "items-4a")).code());
        assertEquals("RESOURCE_NOT_FOUND", errorOf(() -> service.patchItems(a, created.caseId(), 1, List.of(
                new DraftItemInput(UUID.randomUUID(), "LIGHTING", "Этаж 3", "Темно")), "items-4b")).code());

        var tooMany = new ArrayList<DraftItemInput>();
        for (int i = 0; i < 21; i++) tooMany.add(item("OTHER", "Место " + i, "Симптом " + i));
        assertEquals("INVALID_FIELDS", errorOf(() -> service.patchItems(a, created.caseId(), 1, tooMany, "items-4c")).code());

        var own = service.patchItems(a, created.caseId(), 1, List.of(item("LIGHTING", "Этаж 3", "Темно")), "items-4d");
        UUID ownItem = own.items().getFirst().itemId();
        assertEquals("INVALID_FIELDS", errorOf(() -> service.patchItems(a, created.caseId(), 2, List.of(
                new DraftItemInput(ownItem, "LIGHTING", "Этаж 3", "Темно"),
                new DraftItemInput(ownItem, "LIGHTING", "Этаж 3", "Темно")), "items-4e")).code());
        assertEquals("INVALID_FIELDS", errorOf(() -> service.patchItems(a, created.caseId(), 2, List.of(
                item("PLUMBING", "Этаж 3", "Темно")), "items-4g")).code());
        assertEquals("INVALID_FIELDS", errorOf(() -> service.patchItems(a, created.caseId(), 2, List.of(
                item("OTHER", "   ", "Темно")), "items-4h")).code());
        // A rejected command leaves no effect.
        assertEquals(1, count("SELECT count(*) FROM case_item WHERE case_id = ?", created.caseId()));
        assertEquals(2, jdbc.queryForObject("SELECT version FROM service_case WHERE id = ?", Long.class, created.caseId()));
    }

    @Test void confirmRequiresCurrentNonemptyListAndFreezesContent() {
        var created = service.create(a, h1, "Текст", "create-5");
        assertEquals("INVALID_FIELDS", errorOf(() ->
                service.confirm(a, created.caseId(), 1, 1, "confirm-5empty")).code());

        var withSource2 = service.patchSource(a, created.caseId(), 1, "Новый текст", "source-5");
        service.patchItems(a, created.caseId(), 2, List.of(
                item("LIGHTING", "Этаж 3", "Темно"), item("ENTRANCE_DOOR", "Вход", "Не закрывается")), "items-5");
        // Stale sourceRevision is rejected even with the current caseVersion.
        assertEquals("CASE_VERSION_CONFLICT", errorOf(() ->
                service.confirm(a, created.caseId(), 3, 1, "confirm-5stale")).code());

        var confirmed = service.confirm(a, created.caseId(), 3, withSource2.sourceRevision(), "confirm-5");
        assertEquals("CONFIRMED", confirmed.submissionStatus());
        assertEquals(4, confirmed.caseVersion());
        assertEquals(2, confirmed.items().size());
        for (var view : confirmed.items()) assertEquals(2, view.itemVersion());
        assertEquals(2, count("""
                SELECT count(*) FROM item_revision r JOIN case_item i ON i.id = r.item_id
                WHERE i.case_id = ? AND r.confirmed_at IS NOT NULL
                """, created.caseId()));

        assertEquals("CASE_ALREADY_CONFIRMED", errorOf(() ->
                service.patchSource(a, created.caseId(), 4, "Третий текст", "source-5late")).code());
        assertEquals("CASE_ALREADY_CONFIRMED", errorOf(() ->
                service.patchItems(a, created.caseId(), 4, List.of(), "items-5late")).code());
        assertEquals("CASE_ALREADY_CONFIRMED", errorOf(() ->
                service.confirm(a, created.caseId(), 4, 2, "confirm-5twice")).code());
        assertEquals("CONFIRMED", jdbc.queryForObject(
                "SELECT submission_status FROM service_case WHERE id = ?", String.class, created.caseId()));
    }

    @Test void draftIsVisibleOnlyToAuthorAndConfirmedToDispatcher() {
        var draft = draftWithTwoItems();
        for (UUID outsider : List.of(b, c, d)) {
            assertEquals("RESOURCE_NOT_FOUND", errorOf(() -> service.getCase(outsider, draft.caseId())).code());
        }
        assertTrue(service.listCases(b, h1, null, null).items().isEmpty());
        assertTrue(service.listCases(d, h1, null, null).items().isEmpty());
        assertEquals(1, service.listCases(a, h1, null, null).items().size());
        assertEquals("ACTION_FORBIDDEN", errorOf(() -> service.listCases(b, h2, null, null)).code());
        assertEquals("ACTION_FORBIDDEN", errorOf(() -> service.create(c, h1, "Нет membership", "create-6denied")).code());
        assertEquals("RESOURCE_NOT_FOUND", errorOf(() -> service.patchSource(b, draft.caseId(), 2, "Подмена", "source-6")).code());
        assertEquals("RESOURCE_NOT_FOUND", errorOf(() -> service.confirm(d, draft.caseId(), 2, 1, "confirm-6")).code());

        var confirmed = service.confirm(a, draft.caseId(), 2, 1, "confirm-6ok");
        assertEquals(confirmed, service.getCase(d, confirmed.caseId()));
        assertEquals(1, service.listCases(d, h1, null, null).items().size());
        assertEquals("RESOURCE_NOT_FOUND", errorOf(() -> service.getCase(b, confirmed.caseId())).code());
        assertTrue(service.listCases(b, h1, null, null).items().isEmpty());
        assertTrue(service.listCases(c, h2, null, null).items().isEmpty());
    }

    @Test void concurrentPatchesWithSameVersionHaveOneWinner() throws Exception {
        var created = service.create(a, h1, "Текст", "create-7");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                final int n = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return service.patchSource(a, created.caseId(), 1, "Текст " + n, "race-7-" + n);
                    } catch (CaseException e) {
                        return e;
                    }
                }));
            }
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            var results = new ArrayList<>();
            for (var future : futures) results.add(future.get(30, java.util.concurrent.TimeUnit.SECONDS));
            long successes = results.stream().filter(CaseView.class::isInstance).count();
            long conflicts = results.stream().filter(CaseException.class::isInstance)
                    .map(CaseException.class::cast).filter(e -> e.code().equals("CASE_VERSION_CONFLICT")).count();
            assertEquals(1, successes);
            assertEquals(1, conflicts);
        }
        // The loser left no partial rows.
        assertEquals(2, jdbc.queryForObject("SELECT version FROM service_case WHERE id = ?", Long.class, created.caseId()));
        assertEquals(2, count("SELECT count(*) FROM case_revision WHERE case_id = ?", created.caseId()));
        assertEquals(2, count("SELECT count(*) FROM audit_event WHERE case_id = ?", created.caseId()));
        // Only the winner recorded an idempotent command.
        assertEquals(1, count("SELECT count(*) FROM processed_command WHERE actor_id = ? AND operation = 'patch-source'", a));
    }

    @Test void concurrentIdenticalCreateReturnsSingleCase() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<CaseView>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return service.create(a, h1, "Один и тот же текст", "race-8");
                }));
            }
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            var first = futures.get(0).get(30, java.util.concurrent.TimeUnit.SECONDS);
            var second = futures.get(1).get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(first.caseId(), second.caseId());
        }
        assertEquals(1, count("SELECT count(*) FROM service_case WHERE author_id = ?", a));
        assertEquals(1, count("SELECT count(*) FROM audit_event WHERE actor_id = ?", a));
        assertEquals(1, count("SELECT count(*) FROM processed_command WHERE actor_id = ? AND operation = 'create'", a));
    }

    @Test void idempotentReplayReturnsStoredResponseAfterAccessRecheck() {
        var created = service.create(a, h1, "Текст", "create-9");
        service.patchSource(a, created.caseId(), 1, "Изменённый текст", "source-9");
        // Replay returns the stored response (caseVersion 1), not the current state.
        var replay = service.create(a, h1, "Текст", "create-9");
        assertEquals(created.caseId(), replay.caseId());
        assertEquals(1, replay.caseVersion());
        assertEquals(1, count("SELECT count(*) FROM service_case WHERE author_id = ?", a));

        // Same key with a different body conflicts.
        var reused = errorOf(() -> service.create(a, h1, "Другой текст", "create-9"));
        assertEquals("IDEMPOTENCY_KEY_REUSED", reused.code());
        assertEquals(409, reused.httpStatus());

        // Replay of a patch is free of new effects.
        var patchedAgain = service.patchSource(a, created.caseId(), 1, "Изменённый текст", "source-9");
        assertEquals(2, patchedAgain.caseVersion());
        assertEquals(2, count("SELECT count(*) FROM case_revision WHERE case_id = ?", created.caseId()));
        assertEquals(2, jdbc.queryForObject("SELECT version FROM service_case WHERE id = ?", Long.class, created.caseId()));

        // Membership revoked after the fact blocks the replay of a stored response.
        // The deferred current-revision FK requires the case rows to disappear in one transaction.
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("DELETE FROM audit_event WHERE case_id = ?", created.caseId());
            jdbc.update("DELETE FROM case_revision WHERE case_id = ?", created.caseId());
            jdbc.update("DELETE FROM service_case WHERE id = ?", created.caseId());
            jdbc.update("DELETE FROM membership WHERE user_id = ? AND house_id = ?", a, h1);
        });
        assertEquals("ACTION_FORBIDDEN", errorOf(() -> service.create(a, h1, "Текст", "create-9")).code());
    }

    @Test void failureBetweenProjectionAndHistoryLeavesNoRows() {
        var failing = new CaseRepository(jdbc) {
            @Override public void insertAudit(UUID caseId, UUID actorId, String eventType, String payloadJson) {
                throw new IllegalStateException("Injected failure after projection");
            }
        };
        var failingService = new CaseService(failing, transactions);
        assertThrows(IllegalStateException.class,
                () -> failingService.create(a, h1, "Откатываемый текст", "create-10"));
        assertEquals(0, count("SELECT count(*) FROM service_case WHERE author_id = ?", a));
        assertEquals(0, count("SELECT count(*) FROM case_revision WHERE source_text = 'Откатываемый текст'"));
        assertEquals(0, count("SELECT count(*) FROM audit_event WHERE actor_id = ?", a));
        assertEquals(0, count("SELECT count(*) FROM processed_command WHERE actor_id = ?", a));
        // The rolled-back key is not poisoned: the same command can be retried.
        var retried = service.create(a, h1, "Откатываемый текст", "create-10");
        assertEquals(1, retried.caseVersion());
    }

    @Test void listPaginatesStablyWithFilterBoundCursor() {
        var caseIds = new ArrayList<UUID>();
        for (int i = 0; i < 3; i++) {
            caseIds.add(service.create(a, h1, "Текст " + i, "create-11-" + i).caseId());
        }
        var page1 = service.listCases(a, h1, null, 2);
        assertEquals(2, page1.items().size());
        assertNotNull(page1.nextCursor());
        var page2 = service.listCases(a, h1, page1.nextCursor(), 2);
        assertEquals(1, page2.items().size());
        assertNull(page2.nextCursor());
        var seen = new ArrayList<UUID>();
        page1.items().forEach(view -> seen.add(view.caseId()));
        page2.items().forEach(view -> seen.add(view.caseId()));
        assertEquals(new java.util.HashSet<>(caseIds), new java.util.HashSet<>(seen));
        // Stable order: createdAt DESC, id DESC.
        var ordered = new ArrayList<CaseView>();
        ordered.addAll(page1.items());
        ordered.addAll(page2.items());
        for (int i = 1; i < ordered.size(); i++) {
            var prev = ordered.get(i - 1);
            var current = ordered.get(i);
            assertTrue(prev.createdAt().compareTo(current.createdAt()) > 0
                    || (prev.createdAt().equals(current.createdAt()) && prev.caseId().compareTo(current.caseId()) > 0));
        }
        // Cursor is bound to the filter and opaque.
        assertEquals("INVALID_FIELDS", errorOf(() -> service.listCases(a, h2, page1.nextCursor(), 2)).code());
        assertEquals("INVALID_FIELDS", errorOf(() -> service.listCases(a, h1, "not-a-cursor", 2)).code());
        assertEquals("INVALID_FIELDS", errorOf(() -> service.listCases(a, h1, null, 51)).code());
        assertEquals("INVALID_FIELDS", errorOf(() -> service.listCases(a, h1, null, 0)).code());
    }

    @Test void textLimitsCountUnicodeCodePointsNotUtf16Units() {
        String emoji = "😀".repeat(5000);
        assertEquals(10000, emoji.length());
        var created = service.create(a, h1, emoji, "create-12");
        assertEquals(5000, created.sourceText().codePointCount(0, created.sourceText().length()));
        assertEquals("INVALID_FIELDS", errorOf(() ->
                service.create(a, h1, emoji + "!", "create-12b")).code());
        assertEquals("INVALID_FIELDS", errorOf(() ->
                service.create(a, h1, "   ", "create-12c")).code());
        assertEquals("INVALID_FIELDS", errorOf(() -> service.patchItems(a, created.caseId(), 1, List.of(
                item("OTHER", "😀".repeat(301), "Симптом")), "items-12a")).code());
        var patched = service.patchItems(a, created.caseId(), 1, List.of(
                item("OTHER", "😀".repeat(300), "😀".repeat(1000))), "items-12b");
        assertEquals(1, patched.items().size());
        assertEquals("INVALID_FIELDS", errorOf(() ->
                service.create(a, h1, "Текст", "key with spaces")).code());
        assertEquals("INVALID_FIELDS", errorOf(() ->
                service.create(a, h1, "Текст", "k".repeat(201))).code());
    }

    private static UUID itemIdByCategory(ru.koren.cases.application.CaseView view, String category) {
        return view.items().stream()
            .filter(item -> item.category().equals(category))
            .findFirst().orElseThrow().itemId();
    }
}
