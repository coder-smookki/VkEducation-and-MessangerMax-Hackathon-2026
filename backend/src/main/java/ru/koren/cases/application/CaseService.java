package ru.koren.cases.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.koren.cases.domain.CaseProgress;
import ru.koren.cases.domain.ItemState;
import ru.koren.cases.persistence.CaseRepository;
import ru.koren.cases.persistence.CaseRepository.CaseRow;
import ru.koren.cases.persistence.CaseRepository.Cursor;
import ru.koren.cases.persistence.CaseRepository.ProcessedRow;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.time.Clock;
import ru.koren.reports.persistence.ReportRepository;
import ru.koren.reports.application.AnalysisView;
import ru.koren.reports.application.CreateReportResponse;
import ru.koren.reports.application.LinkView;
import ru.koren.reports.application.WorkReportView;
import java.util.Comparator;
import ru.koren.reports.application.LinkInput;
import ru.koren.observations.persistence.ObservationRepository;
import ru.koren.notifications.persistence.OutboxRepository;
import org.springframework.beans.factory.annotation.Value;
import ru.koren.inference.InferenceContract;
import ru.koren.inference.persistence.AnalysisRepository;
import ru.koren.reports.application.ReportSuggestionsView;

/**
 * S1 use cases for drafts and confirmation. actorId must come from server authentication, never a request body.
 * Every command: validate -> authorize -> idempotent replay check -> CAS on caseVersion -> effect -> audit ->
 * processed_command, all in one transaction. Commit is the boundary of success.
 */
@Service
@Profile("!no-db")
public class CaseService {
    static final int MAX_SOURCE_CODE_POINTS = 5000;
    static final int MAX_NEXT_ACTION_CODE_POINTS = 1000;
    static final int MAX_LOCATION_CODE_POINTS = 300;
    static final int MAX_SYMPTOM_CODE_POINTS = 1000;
    static final int MAX_DECISION_REASON_CODE_POINTS = 1000;
    static final int MAX_ITEMS = 20;
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;
    private static final java.util.Set<String> CATEGORIES =
        java.util.Set.of("LIGHTING", "ENTRANCE_DOOR", "CLEANING", "OTHER");
    private static final java.util.Set<String> QUEUE_STATES =
        java.util.Set.of("NEEDS_ACCEPTANCE", "NEEDS_ACTION", "AWAITING_AUTHOR", "ARCHIVED");
    private static final java.util.Set<String> OBSERVATION_VALUES =
        java.util.Set.of("RESTORED", "PERSISTS", "CANNOT_CHECK");
    private static final java.util.Set<String> OBSERVATION_SOURCES =
        java.util.Set.of("AFTER_REPORT", "INDEPENDENT");
    private static final java.util.Set<String> DECISION_ACTIONS =
        java.util.Set.of("ARCHIVE", "WITHDRAW");
    /** History exposes these fields and nothing else; caseId is dropped because the path already carries it. */
    private static final java.util.Map<String, java.util.List<String>> HISTORY_DETAIL_FIELDS =
        java.util.Map.ofEntries(
            java.util.Map.entry("CASE_CREATED", java.util.List.of("houseId", "sourceRevision")),
            java.util.Map.entry("CASE_SOURCE_PATCHED", java.util.List.of("sourceRevision")),
            java.util.Map.entry("CASE_ITEMS_PATCHED", java.util.List.of("itemCount")),
            java.util.Map.entry("CASE_CONFIRMED", java.util.List.of("itemCount")),
            java.util.Map.entry("CASE_ACCEPTED", java.util.List.of()),
            java.util.Map.entry("ITEM_NEXT_ACTION_SET", java.util.List.of("itemId", "ownerId")),
            java.util.Map.entry("REPORT_SAVED", java.util.List.of("reportId", "targetCount")),
            java.util.Map.entry("REPORT_LINK_CONFIRMED",
                java.util.List.of("reportId", "itemId", "workRound", "workStatus")),
            java.util.Map.entry("OBSERVATION_RECORDED",
                java.util.List.of("itemId", "workRound", "value", "source")),
            java.util.Map.entry("ITEM_ADMINISTRATIVE_DECISION", java.util.List.of("itemId", "action")));
    private final CaseRepository repo;
    private final ReportRepository reports;
    private final ObservationRepository observations;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();
    private final OutboxRepository outbox;
    private final AnalysisRepository analyses;
    private final boolean analysisEnabled;
    /** Empty by default; a stand with synthetic data may label its numbers, e.g. CASE_NUMBER_PREFIX=ДЕМО-. */
    @Value("${koren.case-number-prefix:}")
    private String caseNumberPrefix = "";

    public CaseService(CaseRepository repo, ReportRepository reports, ObservationRepository observations,
                       OutboxRepository outbox, AnalysisRepository analyses,
                       PlatformTransactionManager transactions, Clock clock,
                       @Value("${koren.analysis.enabled:false}") boolean analysisEnabled) {
        this.repo = repo;
        this.reports = reports;
        this.observations = observations;
        this.outbox = outbox;
        this.analyses = analyses;
        this.analysisEnabled = analysisEnabled;
        tx = new TransactionTemplate(transactions);
        readTx = new TransactionTemplate(transactions);
        readTx.setReadOnly(true);
        this.clock = clock;
    }

    public CaseView create(UUID authorId, UUID houseId, String sourceText, String idempotencyKey) {
        Objects.requireNonNull(authorId);
        Objects.requireNonNull(houseId);
        validateKey(idempotencyKey);
        validateText("sourceText", sourceText, MAX_SOURCE_CODE_POINTS);
        requireMembership(authorId, houseId);
        String hash = hash("create", houseId, orderedMap("houseId", houseId, "sourceText", sourceText));
        Runnable access = () -> requireMembership(authorId, houseId);
        return runCommand(authorId, "create", idempotencyKey, hash, 201, access, CaseView.class, () -> {
            UUID caseId = UUID.randomUUID();
            repo.insertCase(caseId, houseId, authorId);
            repo.insertCaseRevision(caseId, 1, sourceText);
            repo.insertAudit(caseId, authorId, "CASE_CREATED",
                json.writeValueAsString(orderedMap("caseId", caseId, "houseId", houseId, "sourceRevision", 1)));
            return TxOutcome.fresh(viewOf(repo.findCase(caseId).orElseThrow()));
        });
    }

    public CaseView getCase(UUID actorId, UUID caseId) {
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(caseId);
        return readTx.execute(status -> viewOf(loadVisible(actorId, caseId)));
    }

    /**
     * Asked again immediately before delivery: access may have been revoked after the notification
     * was queued. One rule, one place - this reuses the same check the read path uses.
     */
    public boolean mayRead(UUID actorId, UUID caseId) {
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(caseId);
        return Boolean.TRUE.equals(readTx.execute(status -> {
            try {
                loadVisible(actorId, caseId);
                return true;
            } catch (CaseException denied) {
                return false;
            }
        }));
    }

    public CasePage listCases(UUID actorId, UUID houseId, String queueState, String cursor, Integer limit) {
        Objects.requireNonNull(actorId);
        int pageSize = limit == null ? DEFAULT_LIMIT : limit;
        if (pageSize < 1 || pageSize > MAX_LIMIT) throw CaseException.invalidFields("Invalid limit");
        if (queueState != null) {
            if (!QUEUE_STATES.contains(queueState)) throw CaseException.invalidFields("Unknown queueState");
            if (houseId == null) throw CaseException.invalidFields("queueState requires houseId");
        }
        Cursor after = decodeCursor(cursor, houseId, queueState);
        Timestamp now = Timestamp.from(clock.instant());
        return readTx.execute(status -> {
            // Membership is re-checked on every page; a cursor is not a credential.
            if (houseId != null) requireMembership(actorId, houseId);
            if (queueState != null) requireDispatcherOfHouse(actorId, houseId);
            var rows = repo.listVisibleCases(actorId, houseId, queueState, now, after, pageSize + 1);
            boolean more = rows.size() > pageSize;
            var page = more ? rows.subList(0, pageSize) : rows;
            String next = more ? encodeCursor(houseId, queueState, page.getLast()) : null;
            var ids = page.stream().map(CaseRow::id).toList();
            var itemsByCase = repo.findItemsByCase(ids);
            var textByCase = repo.currentSourceTexts(ids);
            var confirmedByCase = repo.confirmedAtByCase(ids);
            return new CasePage(page.stream()
                .map(r -> viewOf(r, itemsByCase.getOrDefault(r.id(), List.of()),
                    textByCase.get(r.id()), confirmedByCase.get(r.id())))
                .toList(), next);
        });
    }

    public CaseView patchSource(UUID actorId, UUID caseId, long expectedCaseVersion,
                                String sourceText, String idempotencyKey) {
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(caseId);
        validateKey(idempotencyKey);
        validateVersion(expectedCaseVersion);
        validateText("sourceText", sourceText, MAX_SOURCE_CODE_POINTS);
        String hash = hash("patch-source", caseId,
            orderedMap("expectedCaseVersion", expectedCaseVersion, "sourceText", sourceText));
        Runnable access = () -> requireAuthor(actorId, caseId);
        return runCommand(actorId, "patch-source", idempotencyKey, hash, 200, access, CaseView.class, () -> {
            loadDraftForAuthor(actorId, caseId);
            if (repo.bumpDraftCaseVersion(caseId, expectedCaseVersion) == 0) {
                return conflictOrReplay(actorId, "patch-source", idempotencyKey, hash, caseId, access, true, CaseView.class);
            }
            int revision = repo.findCase(caseId).orElseThrow().sourceRevision();
            int nextRevision = revision + 1;
            repo.insertCaseRevision(caseId, nextRevision, sourceText);
            repo.updateSourceRevision(caseId, nextRevision);
            repo.insertAudit(caseId, actorId, "CASE_SOURCE_PATCHED",
                json.writeValueAsString(orderedMap("caseId", caseId, "sourceRevision", nextRevision)));
            return TxOutcome.fresh(viewOf(repo.findCase(caseId).orElseThrow()));
        });
    }

    public CaseView patchItems(UUID actorId, UUID caseId, long expectedCaseVersion,
                               List<DraftItemInput> items, String idempotencyKey) {
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(caseId);
        validateKey(idempotencyKey);
        validateVersion(expectedCaseVersion);
        validateItemsInput(items);
        String hash = hash("patch-items", caseId, canonicalItemsBody(expectedCaseVersion, items));
        Runnable access = () -> requireAuthor(actorId, caseId);
        return runCommand(actorId, "patch-items", idempotencyKey, hash, 200, access, CaseView.class, () -> {
            loadDraftForAuthor(actorId, caseId);
            if (repo.bumpDraftCaseVersion(caseId, expectedCaseVersion) == 0) {
                return conflictOrReplay(actorId, "patch-items", idempotencyKey, hash, caseId, access, true, CaseView.class);
            }
            applyItemReplacement(caseId, items);
            repo.insertAudit(caseId, actorId, "CASE_ITEMS_PATCHED",
                json.writeValueAsString(orderedMap("caseId", caseId, "itemCount", items.size())));
            return TxOutcome.fresh(viewOf(repo.findCase(caseId).orElseThrow()));
        });
    }

    public CaseView confirm(UUID actorId, UUID caseId, long expectedCaseVersion,
                            int sourceRevision, String idempotencyKey) {
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(caseId);
        validateKey(idempotencyKey);
        validateVersion(expectedCaseVersion);
        if (sourceRevision < 1) throw CaseException.invalidFields("Invalid sourceRevision");
        String hash = hash("confirm", caseId,
            orderedMap("expectedCaseVersion", expectedCaseVersion, "sourceRevision", sourceRevision));
        Runnable access = () -> requireAuthor(actorId, caseId);
        return runCommand(actorId, "confirm", idempotencyKey, hash, 200, access, CaseView.class, () -> {
            var row = loadDraftForAuthor(actorId, caseId);
            if (repo.bumpDraftCaseVersion(caseId, expectedCaseVersion) == 0) {
                return conflictOrReplay(actorId, "confirm", idempotencyKey, hash, caseId, access, true, CaseView.class);
            }
            // CAS success pins the row we read: the version is unchanged since our read.
            if (row.sourceRevision() != sourceRevision) throw CaseException.versionConflict();
            var items = repo.findItems(caseId);
            if (items.isEmpty() || items.size() > MAX_ITEMS) {
                throw CaseException.invalidFields("Confirm requires 1-20 items");
            }
            int stamped = repo.confirmCurrentItemRevisions(caseId);
            if (stamped != items.size()) throw new IllegalStateException("Unstamped current item revision");
            if (repo.bumpAllItemVersions(caseId) != items.size()) {
                throw new IllegalStateException("Item set changed during confirm");
            }
            repo.markCaseConfirmed(caseId);
            repo.insertAudit(caseId, actorId, "CASE_CONFIRMED",
                json.writeValueAsString(orderedMap("caseId", caseId, "itemCount", items.size())));
            notifyDispatchers("CASE_CONFIRMED", caseId, actorId, "", orderedMap("caseId", caseId));
            return TxOutcome.fresh(viewOf(repo.findCase(caseId).orElseThrow()));
        });
    }

    /** Full draft-list replacement: kept items keep their revision, changed ones get contentVersion+1. */
    private void applyItemReplacement(UUID caseId, List<DraftItemInput> items) {
        var current = repo.findItems(caseId);
        var currentById = new java.util.HashMap<UUID, CaseRepository.ItemRow>();
        current.forEach(item -> currentById.put(item.id(), item));
        var kept = new HashSet<UUID>();
        int position = 0;
        for (var input : items) {
            position++;
            if (input.itemId() == null) {
                UUID itemId = UUID.randomUUID();
                repo.insertItem(itemId, caseId, position);
                repo.insertItemRevision(itemId, 1, input.category(), input.location(), input.symptom());
                kept.add(itemId);
                continue;
            }
            var existing = currentById.get(input.itemId());
            if (existing == null) throw CaseException.notFound();
            kept.add(existing.id());
            repo.updateItemPosition(existing.id(), position);
            boolean changed = !existing.category().equals(input.category())
                || !existing.location().equals(input.location())
                || !existing.symptom().equals(input.symptom());
            if (changed) {
                int nextVersion = existing.contentVersion() + 1;
                repo.updateItemContent(existing.id(), nextVersion);
                repo.insertItemRevision(existing.id(), nextVersion,
                    input.category(), input.location(), input.symptom());
            }
        }
        for (var item : current) {
            if (kept.contains(item.id())) continue;
            // Links/observations are impossible on a draft, but the rule is enforced explicitly, not only by FK.
            if (repo.countItemDependencies(item.id()) > 0) {
                throw CaseException.invalidTransition("Item with work history cannot be removed");
            }
            repo.deleteItem(item.id());
        }
    }

    private record TxOutcome<T>(T value, boolean replayed) {
        static <T> TxOutcome<T> fresh(T value) { return new TxOutcome<>(value, false); }
        static <T> TxOutcome<T> replay(T value) { return new TxOutcome<>(value, true); }
    }

    private <T> T runCommand(UUID actorId, String operation, String key, String hash, int successStatus,
                             Runnable accessRecheck, Class<T> type, Supplier<TxOutcome<T>> work) {
        try {
            return tx.execute(status -> {
                var prior = repo.findProcessed(actorId, operation, key);
                if (prior.isPresent()) return replay(prior.get(), hash, accessRecheck, type);
                TxOutcome<T> outcome = work.get();
                if (!outcome.replayed()) {
                    repo.insertProcessed(actorId, operation, key, hash, successStatus,
                        json.writeValueAsString(outcome.value()));
                }
                return outcome.value();
            });
        } catch (DuplicateKeyException duplicate) {
            // Loser of a concurrent insert of the same idempotency record: this transaction is aborted.
            // Read the winner in a fresh transaction; a different unique violation is not a replay.
            var winner = repo.findProcessed(actorId, operation, key);
            if (winner.isEmpty()) throw duplicate;
            return replay(winner.get(), hash, accessRecheck, type);
        }
    }

    /** A lost CAS may be a concurrent identical retry that has just committed; check before reporting 409. */
    private <T> TxOutcome<T> conflictOrReplay(UUID actorId, String operation, String key, String hash,
                                              UUID caseId, Runnable accessRecheck, boolean draftOnly,
                                              Class<T> type) {
        var prior = repo.findProcessed(actorId, operation, key);
        if (prior.isPresent()) return TxOutcome.replay(replay(prior.get(), hash, accessRecheck, type));
        // The command's own access rule decides 404/403 here; a dispatcher is not the author.
        accessRecheck.run();
        var current = repo.findCase(caseId).orElseThrow(CaseException::notFound);
        if (draftOnly && !current.isDraft()) throw CaseException.alreadyConfirmed();
        throw CaseException.versionConflict();
    }

    private <T> T replay(ProcessedRow row, String hash, Runnable accessRecheck, Class<T> type) {
        // Access first, always: a revoked actor must not learn anything about a stored command.
        accessRecheck.run();
        if (!row.requestHash().equals(hash)) throw CaseException.idempotencyKeyReused();
        return json.readValue(row.responseBody(), type);
    }

    private CaseRow loadVisible(UUID actorId, UUID caseId) {
        var row = repo.findCase(caseId).orElseThrow(CaseException::notFound);
        if (repo.membershipRole(actorId, row.houseId()).isEmpty()) throw CaseException.notFound();
        if (row.authorId().equals(actorId)) return row;
        if (row.isDraft()) throw CaseException.notFound();
        var role = repo.membershipRole(actorId, row.houseId());
        if (!role.get().equals("DISPATCHER")) throw CaseException.notFound();
        return row;
    }

    private CaseRow loadDraftForAuthor(UUID actorId, UUID caseId) {
        var row = loadVisible(actorId, caseId);
        if (!row.authorId().equals(actorId)) throw CaseException.notFound();
        if (!row.isDraft()) throw CaseException.alreadyConfirmed();
        return row;
    }

    private void requireAuthor(UUID actorId, UUID caseId) {
        var row = loadVisible(actorId, caseId);
        if (!row.authorId().equals(actorId)) throw CaseException.notFound();
    }

    private void requireMembership(UUID userId, UUID houseId) {
        if (repo.membershipRole(userId, houseId).isEmpty()) throw CaseException.forbidden();
    }

    private void requireDispatcherOfHouse(UUID actorId, UUID houseId) {
        var role = repo.membershipRole(actorId, houseId);
        if (role.isEmpty() || !role.get().equals("DISPATCHER")) throw CaseException.forbidden();
    }

    private CaseView viewOf(CaseRow row) {
        return viewOf(row, repo.findItems(row.id()),
            repo.currentSourceText(row.id(), row.sourceRevision()), repo.confirmedAt(row.id()));
    }

    /** A page loads the three projections once for every case, not once per case. */
    private CaseView viewOf(CaseRow row, List<CaseRepository.ItemRow> items,
                            String sourceText, Instant confirmedAt) {
        var states = new ArrayList<ItemState>(items.size());
        var views = new ArrayList<CaseItemView>(items.size());
        for (var item : items) {
            states.add(new ItemState(item.id(), row.authorId(), item.contentVersion(), item.workRound(),
                ItemState.WorkStatus.valueOf(item.workStatus()),
                ItemState.Observation.valueOf(item.observation()),
                ItemState.AdministrativeStatus.valueOf(item.administrativeStatus())));
            views.add(CaseItemView.of(item));
        }
        return new CaseView(row.id(), row.houseId(), row.authorId(), row.submissionStatus(), row.version(),
            row.sourceRevision(), sourceText,
            caseNumberPrefix + row.displayNumber(),
            new CaseReceipt(confirmedAt, instant(row.acceptedAt()), row.acceptedBy()),
            "FULL", views, CaseProgress.from(states), row.createdAt().toInstant());
    }

    private Map<String, Object> canonicalItemsBody(long expectedCaseVersion, List<DraftItemInput> items) {
        var itemMaps = new ArrayList<Map<String, Object>>(items.size());
        for (var item : items) {
            itemMaps.add(orderedMap("itemId", item.itemId(), "category", item.category(),
                "location", item.location(), "symptom", item.symptom()));
        }
        return orderedMap("expectedCaseVersion", expectedCaseVersion, "items", itemMaps);
    }

    /** Deterministic compact JSON: fixed key order, no whitespace ambiguity. */
    private static Map<String, Object> orderedMap(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) map.put((String) keyValues[i], keyValues[i + 1]);
        return map;
    }

    private String hash(String operation, UUID target, Map<String, Object> canonicalBody) {
        return sha256Hex(operation + '\n' + target + '\n' + json.writeValueAsString(canonicalBody));
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder(64);
            for (byte b : digest) hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void validateKey(String key) {
        if (key == null || key.isEmpty() || key.length() > 200
            || key.chars().anyMatch(c -> c < 0x21 || c > 0x7E)) {
            throw CaseException.invalidFields("Invalid Idempotency-Key");
        }
    }

    private static void validateVersion(long expectedCaseVersion) {
        if (expectedCaseVersion < 1) throw CaseException.invalidFields("Invalid expectedCaseVersion");
    }

    private static void validateItemsInput(List<DraftItemInput> items) {
        if (items == null || items.size() > MAX_ITEMS) {
            throw CaseException.invalidFields("Draft allows 0-20 items");
        }
        var ids = new HashSet<UUID>();
        for (var item : items) {
            if (item == null) throw CaseException.invalidFields("Invalid item");
            if (item.itemId() != null && !ids.add(item.itemId())) {
                throw CaseException.invalidFields("Duplicate itemId");
            }
            if (item.category() == null || !CATEGORIES.contains(item.category())) {
                throw CaseException.invalidFields("Invalid category");
            }
            validateText("location", item.location(), MAX_LOCATION_CODE_POINTS);
            validateText("symptom", item.symptom(), MAX_SYMPTOM_CODE_POINTS);
        }
    }

    /** Limits count Unicode code points, not UTF-16 units. */
    private static void validateText(String field, String value, int maxCodePoints) {
        if (value == null || value.codePointCount(0, value.length()) > maxCodePoints
            || value.codePoints().noneMatch(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c))) {
            throw CaseException.invalidFields("Invalid " + field);
        }
    }

    private static Cursor decodeCursor(String cursor, UUID houseId, String queueState) {
        if (cursor == null) return null;
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 5 || !"v2".equals(parts[0])) throw CaseException.invalidFields("Invalid cursor");
            String cursorHouse = "-".equals(parts[1]) ? null : parts[1];
            String cursorQueue = "-".equals(parts[2]) ? null : parts[2];
            if (!Objects.equals(cursorHouse, houseId == null ? null : houseId.toString())
                || !Objects.equals(cursorQueue, queueState)) {
                throw CaseException.invalidFields("Invalid cursor");
            }
            long micros = Long.parseLong(parts[3]);
            long millis = Math.floorDiv(micros, 1000);
            Timestamp createdAt = new Timestamp(millis);
            createdAt.setNanos(createdAt.getNanos() + (int) Math.floorMod(micros, 1000) * 1000);
            return new Cursor(createdAt, UUID.fromString(parts[4]));
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            throw CaseException.invalidFields("Invalid cursor");
        }
    }

    private static String encodeCursor(UUID houseId, String queueState, CaseRow last) {
        Timestamp ts = last.createdAt();
        long micros = ts.getTime() * 1000 + (ts.getNanos() % 1_000_000) / 1000;
        String raw = "v2|" + (houseId == null ? "-" : houseId) + "|" + (queueState == null ? "-" : queueState)
            + "|" + micros + "|" + last.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** Acceptance is a separate fact: it never touches work status, round or observation. */
    public CaseView accept(UUID actorId, UUID caseId, long expectedCaseVersion, String idempotencyKey) {
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(caseId);
        validateKey(idempotencyKey);
        validateVersion(expectedCaseVersion);
        String hash = hash("accept", caseId, orderedMap("expectedCaseVersion", expectedCaseVersion));
        Runnable access = () -> requireDispatcherOfCase(actorId, caseId);
        return runCommand(actorId, "accept", idempotencyKey, hash, 200, access, CaseView.class, () -> {
            var row = loadVisible(actorId, caseId);
            requireDispatcherOfCase(actorId, caseId);
            if (!"CONFIRMED".equals(row.submissionStatus())) {
                throw CaseException.invalidTransition("Only a confirmed case can be accepted");
            }
            if (row.acceptedAt() != null) {
                throw CaseException.invalidTransition("The case is already accepted");
            }
            if (repo.bumpConfirmedCaseVersion(caseId, expectedCaseVersion) == 0) {
                return conflictOrReplay(actorId, "accept", idempotencyKey, hash, caseId, access, false, CaseView.class);
            }
            // Re-accepting is not an error, but it must not overwrite who accepted first.
            repo.acceptCase(caseId, actorId);
            repo.insertAudit(caseId, actorId, "CASE_ACCEPTED",
                json.writeValueAsString(orderedMap("caseId", caseId)));
            notifyAuthor("CASE_ACCEPTED", caseId, actorId, "", orderedMap("caseId", caseId));
            return TxOutcome.fresh(viewOf(repo.findCase(caseId).orElseThrow()));
        });
    }

    public CaseView setNextAction(UUID actorId, UUID itemId, long expectedCaseVersion, long expectedItemVersion,
                                  String text, UUID ownerId, Instant updateDueAt, String idempotencyKey) {
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(itemId);
        validateKey(idempotencyKey);
        validateVersion(expectedCaseVersion);
        validateVersion(expectedItemVersion);
        validateText("text", text, MAX_NEXT_ACTION_CODE_POINTS);
        if (ownerId == null) throw CaseException.invalidFields("ownerId is required");
        String hash = hash("next-action", itemId, orderedMap(
            "expectedCaseVersion", expectedCaseVersion, "expectedItemVersion", expectedItemVersion,
            "text", text, "ownerId", ownerId,
            "updateDueAt", updateDueAt == null ? null : updateDueAt.toString()));
        Runnable access = () -> requireDispatcherOfItem(actorId, itemId);
        return runCommand(actorId, "next-action", idempotencyKey, hash, 200, access, CaseView.class, () -> {
            var ctx = requireDispatcherOfItem(actorId, itemId);
            // A replay returns its committed result even after the saved deadline has passed.
            if (updateDueAt != null && updateDueAt.isBefore(clock.instant())) {
                throw CaseException.invalidFields("updateDueAt must not be in the past");
            }
            if (ctx.isDraft()) {
                throw CaseException.invalidTransition("A next action needs a confirmed case");
            }
            if (!"ACTIVE".equals(ctx.administrativeStatus())) {
                throw CaseException.invalidTransition("A next action needs an active item");
            }
            var ownerRole = repo.membershipRole(ownerId, ctx.houseId());
            if (ownerRole.isEmpty() || !"DISPATCHER".equals(ownerRole.get())) {
                throw CaseException.invalidFields("ownerId must be an active dispatcher of this house");
            }
            if (repo.bumpConfirmedCaseVersion(ctx.caseId(), expectedCaseVersion) == 0) {
                return conflictOrReplay(actorId, "next-action", idempotencyKey, hash, ctx.caseId(), access, false, CaseView.class);
            }
            if (repo.setNextAction(itemId, expectedItemVersion, text, ownerId,
                updateDueAt == null ? null : Timestamp.from(updateDueAt)) == 0) {
                throw CaseException.itemVersionConflict();
            }
            repo.insertAudit(ctx.caseId(), actorId, "ITEM_NEXT_ACTION_SET",
                json.writeValueAsString(orderedMap("caseId", ctx.caseId(), "itemId", itemId,
                    "ownerId", ownerId)));
            notifyAuthor("ITEM_NEXT_ACTION_SET", ctx.caseId(), actorId, itemId.toString(),
                orderedMap("caseId", ctx.caseId(), "itemId", itemId));
            return TxOutcome.fresh(viewOf(repo.findCase(ctx.caseId()).orElseThrow()));
        });
    }

    private void requireDispatcherOfCase(UUID actorId, UUID caseId) {
        var row = repo.findCase(caseId).orElseThrow(CaseException::notFound);
        var role = repo.membershipRole(actorId, row.houseId());
        if (role.isEmpty() || !role.get().equals("DISPATCHER")) throw CaseException.forbidden();
    }

    /** A per-item command authorizes itself through the case that owns the item. */
    private CaseRepository.ItemContextRow requireDispatcherOfItem(UUID actorId, UUID itemId) {
        var ctx = repo.findItemContext(itemId).orElseThrow(CaseException::notFound);
        loadVisible(actorId, ctx.caseId());
        requireDispatcherOfCase(actorId, ctx.caseId());
        return ctx;
    }

    private static Instant instant(java.sql.Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static void validateTargetItemIds(List<UUID> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            throw CaseException.invalidFields("targetItemIds must not be empty");
        }
        if (itemIds.contains(null)) {
            throw CaseException.invalidFields("targetItemIds must not contain null");
        }
        if (itemIds.size() > MAX_ITEMS) {
            throw CaseException.invalidFields("Too many targetItemIds");
        }
        if (new HashSet<>(itemIds).size() != itemIds.size()) {
            throw CaseException.invalidFields("Duplicate targetItemIds");
        }
    }

    public CreateReportResponse createReport(UUID actorId, UUID caseId, long expectedCaseVersion,
                                             String reportText, List<UUID> targetItemIds,
                                             String idempotencyKey) {
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(caseId);
        validateKey(idempotencyKey);
        validateVersion(expectedCaseVersion);
        validateText("reportText", reportText, MAX_SOURCE_CODE_POINTS);
        validateTargetItemIds(targetItemIds);
        String hash = hash("create-report", caseId, orderedMap(
            "expectedCaseVersion", expectedCaseVersion, "reportText", reportText,
            "targetItemIds", targetItemIds.stream().map(UUID::toString).sorted().toList()));
        Runnable access = () -> requireDispatcherOfCase(actorId, caseId);
        return runCommand(actorId, "create-report", idempotencyKey, hash, 201, access,
            CreateReportResponse.class, () -> {
                var row = loadVisible(actorId, caseId);
                requireDispatcherOfCase(actorId, caseId);
                if (row.isDraft()) {
                    throw CaseException.invalidTransition("A report needs a confirmed case");
                }
                if (reports.countItemsInCase(caseId, targetItemIds) != targetItemIds.size()) {
                    throw CaseException.invalidFields("targetItemIds must be items of this case");
                }
                if (repo.bumpConfirmedCaseVersion(caseId, expectedCaseVersion) == 0) {
                    return conflictOrReplay(actorId, "create-report", idempotencyKey, hash, caseId, access,
                        false, CreateReportResponse.class);
                }
                UUID reportId = UUID.randomUUID();
                reports.insertReport(reportId, caseId, actorId, reportText);
                reports.insertTargets(reportId, caseId, targetItemIds);
                repo.insertAudit(caseId, actorId, "REPORT_SAVED", json.writeValueAsString(
                    orderedMap("caseId", caseId, "reportId", reportId, "targetCount", targetItemIds.size())));
                // The analysis is only queued here, in the same transaction; Python is called later,
                // outside it, so saving the report never depends on the analysis succeeding.
                if (analysisEnabled) analyses.enqueue(reportId, caseId, row.sourceRevision());
                // Saving text changes no item: no round, no work status, no observation.
                var stored = reports.findReport(reportId).orElseThrow();
                var report = new WorkReportView(reportId, caseId, actorId, reportText, targetItemIds,
                    stored.createdAt().toInstant(), List.of());
                return TxOutcome.fresh(new CreateReportResponse(report,
                    viewOf(repo.findCase(caseId).orElseThrow()),
                    new AnalysisView(analysisEnabled ? "QUEUED" : "MANUAL_REQUIRED")));
            });
    }

    /**
     * The analysis of one report as its author or a dispatcher may see it. Reading never queues work.
     * RUNNING and STALE are not stored: they are what QUEUED and READY mean right now.
     */
    public ReportSuggestionsView getSuggestions(UUID actorId, UUID reportId) {
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(reportId);
        return readTx.execute(status -> {
            var report = reports.findReport(reportId).orElseThrow(CaseException::notFound);
            var caseRow = loadVisible(actorId, report.caseId());
            // Authorize first. Disabling analysis also hides results persisted before the restart.
            if (!analysisEnabled) return new ReportSuggestionsView("MANUAL_REQUIRED", null, null);
            var analysis = analyses.findByReport(reportId).orElse(null);
            if (analysis == null) return new ReportSuggestionsView("MANUAL_REQUIRED", null, null);
            return switch (analysis.status()) {
                case "QUEUED" -> new ReportSuggestionsView(analysis.leased() ? "RUNNING" : "QUEUED",
                    analysis.id(), null);
                case "READY" -> readyOrStale(caseRow, analysis);
                default -> new ReportSuggestionsView("MANUAL_REQUIRED", analysis.id(), null);
            };
        });
    }

    /** A READY result is served only while the snapshot it was built from still describes the case. */
    private ReportSuggestionsView readyOrStale(CaseRow caseRow, AnalysisRepository.AnalysisRow analysis) {
        var stored = analyses.findSuggestion(analysis.suggestionId()).orElseThrow();
        var result = json.readValue(stored.payload(), InferenceContract.Response.class);
        var current = new java.util.HashMap<UUID, CaseRepository.ItemRow>();
        repo.findItems(caseRow.id()).forEach(item -> current.put(item.id(), item));
        boolean fresh = ru.koren.inference.AnalysisPolicy.supports(result)
            && stored.sourceRevision() == caseRow.sourceRevision()
            && result.suggestions().stream().allMatch(suggestion -> {
            var item = current.get(suggestion.itemId());
            return item != null && item.contentVersion() == suggestion.contentVersion()
                && item.workRound() == suggestion.workRound();
        });
        // A stale hint is withheld, not shown with a warning: it describes a round that is over.
        return fresh
            ? new ReportSuggestionsView("READY", analysis.id(), result)
            : new ReportSuggestionsView("STALE", analysis.id(), null);
    }

    public WorkReportView getReport(UUID actorId, UUID reportId) {
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(reportId);
        return readTx.execute(status -> {
            var report = reports.findReport(reportId).orElseThrow(CaseException::notFound);
            // Visibility of the report follows visibility of its case.
            loadVisible(actorId, report.caseId());
            return new WorkReportView(report.id(), report.caseId(), report.actorId(), report.reportText(),
                reports.findTargets(reportId), report.createdAt().toInstant(),
                reports.findLinks(reportId).stream()
                    .map(link -> new LinkView(link.itemId(), link.contentVersion(), link.workRound(),
                        link.workStatus(), link.confirmedBy(), link.createdAt().toInstant()))
                    .toList());
        });
    }

    private static final java.util.Set<String> LINK_WORK_STATUSES =
        java.util.Set.of("PLANNED", "REPORTED_DONE");

    private void requireDispatcherOfReport(UUID actorId, UUID reportId) {
        var report = reports.findReport(reportId).orElseThrow(CaseException::notFound);
        requireDispatcherOfCase(actorId, report.caseId());
    }

    private static void validateLinks(List<LinkInput> links) {
        if (links == null || links.isEmpty()) throw CaseException.invalidFields("links must not be empty");
        if (links.size() > MAX_ITEMS) throw CaseException.invalidFields("Too many links");
        var seen = new HashSet<UUID>();
        for (LinkInput link : links) {
            if (link == null || link.itemId() == null) {
                throw CaseException.invalidFields("Each link needs an itemId");
            }
            if (!seen.add(link.itemId())) throw CaseException.invalidFields("Duplicate itemId in links");
            if (!LINK_WORK_STATUSES.contains(link.workStatus())) {
                // NOT_ASSIGNED is the absence of news about work; a link is news, so it cannot mean that.
                throw CaseException.invalidFields("workStatus must be PLANNED or REPORTED_DONE");
            }
            validateVersion(link.expectedItemVersion());
            if (link.contentVersion() < 1) throw CaseException.invalidFields("Invalid contentVersion");
            if (link.expectedWorkRound() < 0) throw CaseException.invalidFields("Invalid expectedWorkRound");
        }
    }

    private static List<Map<String, Object>> canonicalLinks(List<LinkInput> links) {
        return links.stream()
            .sorted(Comparator.comparing(link -> link.itemId().toString()))
            .map(link -> orderedMap("itemId", link.itemId().toString(),
                "expectedItemVersion", link.expectedItemVersion(),
                "contentVersion", link.contentVersion(),
                "expectedWorkRound", link.expectedWorkRound(),
                "workStatus", link.workStatus()))
            .toList();
    }

    public CaseView confirmLinks(UUID actorId, UUID reportId, long expectedCaseVersion,
                                 List<LinkInput> links, String idempotencyKey) {
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(reportId);
        validateKey(idempotencyKey);
        validateVersion(expectedCaseVersion);
        validateLinks(links);
        String hash = hash("confirm-links", reportId, orderedMap(
            "expectedCaseVersion", expectedCaseVersion, "links", canonicalLinks(links)));
        Runnable access = () -> requireDispatcherOfReport(actorId, reportId);
        return runCommand(actorId, "confirm-links", idempotencyKey, hash, 200, access, CaseView.class, () -> {
            var report = reports.findReport(reportId).orElseThrow(CaseException::notFound);
            requireDispatcherOfCase(actorId, report.caseId());
            var targets = new HashSet<>(reports.findTargets(reportId));
            // One CAS for the whole set: either every link lands, or the transaction rolls back.
            if (repo.bumpConfirmedCaseVersion(report.caseId(), expectedCaseVersion) == 0) {
                return conflictOrReplay(actorId, "confirm-links", idempotencyKey, hash, report.caseId(),
                    access, false, CaseView.class);
            }
            for (LinkInput link : links) {
                if (!targets.contains(link.itemId())) {
                    // A fitting suggestion outside the report's scope still does not authorize a link.
                    throw CaseException.invalidFields("An item outside targetItemIds cannot be linked");
                }
                if (reports.linkExists(reportId, link.itemId())) {
                    throw CaseException.linkAlreadyExists();
                }
                var ctx = repo.findItemContext(link.itemId()).orElseThrow(CaseException::notFound);
                if (!ctx.caseId().equals(report.caseId())) throw CaseException.notFound();
                if (!"ACTIVE".equals(ctx.administrativeStatus())) {
                    throw CaseException.invalidTransition("An inactive item takes no new round");
                }
                if (ctx.contentVersion() != link.contentVersion()
                    || ctx.workRound() != link.expectedWorkRound()) {
                    throw CaseException.staleContext();
                }
                if (repo.openRoundForLink(link.itemId(), link.expectedItemVersion(), link.contentVersion(),
                    link.expectedWorkRound(), link.workStatus()) == 0) {
                    throw CaseException.itemVersionConflict();
                }
                reports.insertLink(reportId, link.itemId(), report.caseId(), link.contentVersion(),
                    link.expectedWorkRound() + 1, link.workStatus(), actorId);
                repo.insertAudit(report.caseId(), actorId, "REPORT_LINK_CONFIRMED",
                    json.writeValueAsString(orderedMap("caseId", report.caseId(), "reportId", reportId,
                        "itemId", link.itemId(), "workRound", link.expectedWorkRound() + 1,
                        "workStatus", link.workStatus())));
                // Per item, so the dedup key differs: one case version covers the whole set.
                notifyAuthor("REPORT_LINK_CONFIRMED", report.caseId(), actorId, link.itemId().toString(),
                    orderedMap("caseId", report.caseId(), "itemId", link.itemId()));
            }
            return TxOutcome.fresh(viewOf(repo.findCase(report.caseId()).orElseThrow()));
        });
    }

    public CaseView observe(UUID actorId, UUID itemId, long expectedCaseVersion, long expectedItemVersion,
                            int contentVersion, int workRound, String value, String source,
                            String idempotencyKey) {
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(itemId);
        validateKey(idempotencyKey);
        validateVersion(expectedCaseVersion);
        validateVersion(expectedItemVersion);
        if (contentVersion < 1) throw CaseException.invalidFields("Invalid contentVersion");
        if (workRound < 0) throw CaseException.invalidFields("Invalid workRound");
        if (!OBSERVATION_VALUES.contains(value)) {
            // NOT_RECEIVED is the absence of an answer, so it is not a value the author can send.
            throw CaseException.invalidFields("value must be RESTORED, PERSISTS or CANNOT_CHECK");
        }
        if (!OBSERVATION_SOURCES.contains(source)) {
            throw CaseException.invalidFields("source must be AFTER_REPORT or INDEPENDENT");
        }
        String hash = hash("observe", itemId, orderedMap(
            "expectedCaseVersion", expectedCaseVersion, "expectedItemVersion", expectedItemVersion,
            "contentVersion", contentVersion, "workRound", workRound,
            "value", value, "source", source));
        Runnable access = () -> requireAuthorOfItem(actorId, itemId);
        return runCommand(actorId, "observe", idempotencyKey, hash, 200, access, CaseView.class, () -> {
            var ctx = requireAuthorOfItem(actorId, itemId);
            if (ctx.isDraft()) {
                throw CaseException.invalidTransition("An observation needs a confirmed case");
            }
            // A stale answer deserves its own code: the round moved on, the author must look again.
            if (ctx.contentVersion() != contentVersion || ctx.workRound() != workRound) {
                throw CaseException.staleContext();
            }
            var state = new ItemState(itemId, actorId, ctx.contentVersion(), ctx.workRound(),
                ItemState.WorkStatus.valueOf(ctx.workStatus()),
                ItemState.Observation.valueOf(ctx.observation()),
                ItemState.AdministrativeStatus.valueOf(ctx.administrativeStatus()));
            ItemState observed;
            try {
                // The domain rules live in ItemState and are covered by ItemStateTest; this only translates.
                observed = state.observe(actorId, ItemState.Observation.valueOf(value),
                    ItemState.ObservationSource.valueOf(source), contentVersion, workRound);
            } catch (SecurityException denied) {
                throw CaseException.forbidden();
            } catch (IllegalStateException | IllegalArgumentException rejected) {
                throw CaseException.invalidTransition(rejected.getMessage());
            }
            if (repo.bumpConfirmedCaseVersion(ctx.caseId(), expectedCaseVersion) == 0) {
                return conflictOrReplay(actorId, "observe", idempotencyKey, hash, ctx.caseId(), access,
                    false, CaseView.class);
            }
            if (repo.applyObservation(itemId, expectedItemVersion, contentVersion, workRound,
                observed.observation().name()) == 0) {
                throw CaseException.itemVersionConflict();
            }
            observations.insertObservation(UUID.randomUUID(), itemId, ctx.caseId(), actorId,
                contentVersion, workRound, value, source);
            repo.insertAudit(ctx.caseId(), actorId, "OBSERVATION_RECORDED",
                json.writeValueAsString(orderedMap("caseId", ctx.caseId(), "itemId", itemId,
                    "workRound", workRound, "value", value, "source", source)));
            notifyDispatchers("OBSERVATION_RECORDED", ctx.caseId(), actorId, itemId.toString(),
                orderedMap("caseId", ctx.caseId(), "itemId", itemId));
            return TxOutcome.fresh(viewOf(repo.findCase(ctx.caseId()).orElseThrow()));
        });
    }

    /** ARCHIVE is the dispatcher's, WITHDRAW is the author's; both are final and both keep total intact. */
    public CaseView decide(UUID actorId, UUID itemId, long expectedCaseVersion, long expectedItemVersion,
                           String action, String reason, String idempotencyKey) {
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(itemId);
        validateKey(idempotencyKey);
        validateVersion(expectedCaseVersion);
        validateVersion(expectedItemVersion);
        if (action == null || !DECISION_ACTIONS.contains(action)) throw CaseException.invalidFields("Unknown action");
        validateText("reason", reason, MAX_DECISION_REASON_CODE_POINTS);
        boolean withdrawal = "WITHDRAW".equals(action);
        var decision = withdrawal
            ? ItemState.AdministrativeStatus.WITHDRAWN
            : ItemState.AdministrativeStatus.ARCHIVED;
        String hash = hash("administrative-decision", itemId, orderedMap(
            "expectedCaseVersion", expectedCaseVersion, "expectedItemVersion", expectedItemVersion,
            "action", action, "reason", reason));
        Runnable access = withdrawal
            ? () -> requireAuthorOfItem(actorId, itemId)
            : () -> requireDispatcherOfItem(actorId, itemId);
        return runCommand(actorId, "administrative-decision", idempotencyKey, hash, 200, access, CaseView.class, () -> {
            var ctx = withdrawal ? requireAuthorOfItem(actorId, itemId) : requireDispatcherOfItem(actorId, itemId);
            if (ctx.isDraft()) {
                throw CaseException.invalidTransition("An administrative decision needs a confirmed case");
            }
            var owner = repo.findCase(ctx.caseId()).orElseThrow(CaseException::notFound);
            var state = new ItemState(itemId, owner.authorId(), ctx.contentVersion(), ctx.workRound(),
                ItemState.WorkStatus.valueOf(ctx.workStatus()),
                ItemState.Observation.valueOf(ctx.observation()),
                ItemState.AdministrativeStatus.valueOf(ctx.administrativeStatus()));
            try {
                state.decide(actorId, decision);
            } catch (SecurityException denied) {
                throw CaseException.forbidden();
            } catch (IllegalStateException | IllegalArgumentException rejected) {
                throw CaseException.invalidTransition(rejected.getMessage());
            }
            if (repo.bumpConfirmedCaseVersion(ctx.caseId(), expectedCaseVersion) == 0) {
                return conflictOrReplay(actorId, "administrative-decision", idempotencyKey, hash,
                    ctx.caseId(), access, false, CaseView.class);
            }
            if (repo.applyDecision(itemId, expectedItemVersion, decision.name(), reason) == 0) {
                throw CaseException.itemVersionConflict();
            }
            repo.insertAudit(ctx.caseId(), actorId, "ITEM_ADMINISTRATIVE_DECISION",
                json.writeValueAsString(orderedMap("caseId", ctx.caseId(), "itemId", itemId, "action", action)));
            // The author withdraws, so the dispatchers hear it; the dispatcher archives, so the author does.
            if (withdrawal) {
                notifyDispatchers("ITEM_WITHDRAWN", ctx.caseId(), actorId, itemId.toString(),
                    orderedMap("caseId", ctx.caseId(), "itemId", itemId));
            } else {
                notifyAuthor("ITEM_ARCHIVED", ctx.caseId(), actorId, itemId.toString(),
                    orderedMap("caseId", ctx.caseId(), "itemId", itemId));
            }
            return TxOutcome.fresh(viewOf(repo.findCase(ctx.caseId()).orElseThrow()));
        });
    }

    public HistoryPage history(UUID actorId, UUID caseId, String cursor, Integer limit) {
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(caseId);
        int pageSize = limit == null ? DEFAULT_LIMIT : limit;
        if (pageSize < 1 || pageSize > MAX_LIMIT) throw CaseException.invalidFields("Invalid limit");
        Cursor after = decodeHistoryCursor(cursor, caseId);
        return readTx.execute(status -> {
            // Access is re-checked on every page; a cursor carries no rights of its own.
            loadVisible(actorId, caseId);
            var rows = repo.listAudit(caseId, after == null ? null : after.createdAt(),
                after == null ? null : after.id(), pageSize + 1);
            boolean more = rows.size() > pageSize;
            var page = more ? rows.subList(0, pageSize) : rows;
            String next = more ? encodeHistoryCursor(caseId, page.getLast()) : null;
            return new HistoryPage(page.stream().map(this::historyEventOf).toList(), next);
        });
    }

    /** An unknown event type still appears, with empty details: history must not silently lose events. */
    private HistoryEvent historyEventOf(CaseRepository.AuditRow row) {
        var allowed = HISTORY_DETAIL_FIELDS.getOrDefault(row.eventType(), java.util.List.of());
        var details = new java.util.LinkedHashMap<String, Object>();
        if (!allowed.isEmpty() && row.payload() != null) {
            var payload = json.readTree(row.payload());
            for (String field : allowed) {
                var value = payload.get(field);
                if (value != null && !value.isNull()) {
                    details.put(field, json.treeToValue(value, Object.class));
                }
            }
        }
        return new HistoryEvent(row.id(), row.actorId(), row.eventType(),
            row.createdAt().toInstant(), details);
    }

    /**
     * Queues notifications inside the caller's transaction. The state change and its announcement
     * commit together: MAX failing later must not undo the acceptance, and the acceptance must not
     * lose the notification. The actor is skipped - nobody needs news of their own command.
     */
    private void queueNotifications(String eventType, UUID caseId, long caseVersion, UUID actorId,
                                    String keyPart, List<UUID> recipients, Map<String, Object> details) {
        String payload = json.writeValueAsString(details);
        for (UUID recipient : recipients) {
            if (recipient.equals(actorId)) continue;
            outbox.enqueue(eventType + ":" + caseId + ":" + caseVersion + ":" + keyPart + ":" + recipient,
                caseId, recipient, eventType, payload);
        }
    }

    /** The version in the dedup key is the one this command produced, so a repeat is the same news. */
    private void notifyAuthor(String eventType, UUID caseId, UUID actorId, String keyPart,
                              Map<String, Object> details) {
        var row = repo.findCase(caseId).orElseThrow();
        queueNotifications(eventType, caseId, row.version(), actorId, keyPart,
            List.of(row.authorId()), details);
    }

    private void notifyDispatchers(String eventType, UUID caseId, UUID actorId, String keyPart,
                                   Map<String, Object> details) {
        var row = repo.findCase(caseId).orElseThrow();
        queueNotifications(eventType, caseId, row.version(), actorId, keyPart,
            repo.activeDispatchers(row.houseId()), details);
    }

    private static Cursor decodeHistoryCursor(String cursor, UUID caseId) {
        if (cursor == null) return null;
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 4 || !"h1".equals(parts[0])) throw CaseException.invalidFields("Invalid cursor");
            if (!parts[1].equals(caseId.toString())) throw CaseException.invalidFields("Invalid cursor");
            long micros = Long.parseLong(parts[2]);
            long millis = Math.floorDiv(micros, 1000);
            Timestamp createdAt = new Timestamp(millis);
            createdAt.setNanos(createdAt.getNanos() + (int) Math.floorMod(micros, 1000) * 1000);
            return new Cursor(createdAt, UUID.fromString(parts[3]));
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            throw CaseException.invalidFields("Invalid cursor");
        }
    }

    private static String encodeHistoryCursor(UUID caseId, CaseRepository.AuditRow last) {
        Timestamp ts = last.createdAt();
        long micros = ts.getTime() * 1000 + (ts.getNanos() % 1_000_000) / 1000;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            ("h1|" + caseId + "|" + micros + "|" + last.id()).getBytes(StandardCharsets.UTF_8));
    }

    private CaseRepository.ItemContextRow requireAuthorOfItem(UUID actorId, UUID itemId) {
        var ctx = repo.findItemContext(itemId).orElseThrow(CaseException::notFound);
        var row = loadVisible(actorId, ctx.caseId());
        if (!row.authorId().equals(actorId)) throw CaseException.forbidden();
        return ctx;
    }
}
