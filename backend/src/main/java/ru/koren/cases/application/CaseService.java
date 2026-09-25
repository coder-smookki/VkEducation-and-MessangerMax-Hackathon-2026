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
    static final int MAX_ITEMS = 20;
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;
    private static final java.util.Set<String> CATEGORIES =
            java.util.Set.of("LIGHTING", "ENTRANCE_DOOR", "CLEANING", "OTHER");
    private static final java.util.Set<String> QUEUE_STATES =
        java.util.Set.of("NEEDS_ACCEPTANCE", "NEEDS_ACTION", "AWAITING_AUTHOR", "ARCHIVED");
    private final CaseRepository repo;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    public CaseService(CaseRepository repo, PlatformTransactionManager transactions, Clock clock) {
        this.repo = repo;
        this.clock = clock;
        tx = new TransactionTemplate(transactions);
        readTx = new TransactionTemplate(transactions);
        readTx.setReadOnly(true);
    }

    public CaseView create(UUID authorId, UUID houseId, String sourceText, String idempotencyKey) {
        Objects.requireNonNull(authorId);
        Objects.requireNonNull(houseId);
        validateKey(idempotencyKey);
        validateText("sourceText", sourceText, MAX_SOURCE_CODE_POINTS);
        requireMembership(authorId, houseId);
        String hash = hash("create", houseId, orderedMap("houseId", houseId, "sourceText", sourceText));
        Runnable access = () -> requireMembership(authorId, houseId);
        return runCommand(authorId, "create", idempotencyKey, hash, 201, access, () -> {
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
            return new CasePage(page.stream().map(this::viewOf).toList(), next);
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
        return runCommand(actorId, "patch-source", idempotencyKey, hash, 200, access, () -> {
            loadDraftForAuthor(actorId, caseId);
            if (repo.bumpDraftCaseVersion(caseId, expectedCaseVersion) == 0) {
                return conflictOrReplay(actorId, "patch-source", idempotencyKey, hash, caseId, access, true);
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
        return runCommand(actorId, "patch-items", idempotencyKey, hash, 200, access, () -> {
            loadDraftForAuthor(actorId, caseId);
            if (repo.bumpDraftCaseVersion(caseId, expectedCaseVersion) == 0) {
                return conflictOrReplay(actorId, "patch-items", idempotencyKey, hash, caseId, access, true);
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
        return runCommand(actorId, "confirm", idempotencyKey, hash, 200, access, () -> {
            var row = loadDraftForAuthor(actorId, caseId);
            if (repo.bumpDraftCaseVersion(caseId, expectedCaseVersion) == 0) {
                return conflictOrReplay(actorId, "confirm", idempotencyKey, hash, caseId, access, true);
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

    private record TxOutcome(CaseView view, boolean replayed) {
        static TxOutcome fresh(CaseView view) { return new TxOutcome(view, false); }
        static TxOutcome replay(CaseView view) { return new TxOutcome(view, true); }
    }

    private CaseView runCommand(UUID actorId, String operation, String key, String hash, int successStatus,
                                Runnable accessRecheck, Supplier<TxOutcome> work) {
        try {
            return tx.execute(status -> {
                var prior = repo.findProcessed(actorId, operation, key);
                if (prior.isPresent()) return replay(prior.get(), hash, accessRecheck);
                TxOutcome outcome = work.get();
                if (!outcome.replayed()) {
                    repo.insertProcessed(actorId, operation, key, hash, successStatus,
                            json.writeValueAsString(outcome.view()));
                }
                return outcome.view();
            });
        } catch (DuplicateKeyException duplicate) {
            // Loser of a concurrent insert of the same idempotency record: this transaction is aborted.
            // Read the winner in a fresh transaction; a different unique violation is not a replay.
            var winner = repo.findProcessed(actorId, operation, key);
            if (winner.isEmpty()) throw duplicate;
            return replay(winner.get(), hash, accessRecheck);
        }
    }

    /** A lost CAS may be a concurrent identical retry that has just committed; check before reporting 409. */
    private TxOutcome conflictOrReplay(UUID actorId, String operation, String key, String hash,
                                       UUID caseId, Runnable accessRecheck, boolean draftOnly) {
        var prior = repo.findProcessed(actorId, operation, key);
        if (prior.isPresent()) return TxOutcome.replay(replay(prior.get(), hash, accessRecheck));
        // The command's own access rule decides 404/403 here; a dispatcher is not the author.
        accessRecheck.run();
        var current = repo.findCase(caseId).orElseThrow(CaseException::notFound);
        if (draftOnly && !current.isDraft()) throw CaseException.alreadyConfirmed();
        throw CaseException.versionConflict();
    }

    private CaseView replay(ProcessedRow row, String hash, Runnable accessRecheck) {
        if (!row.requestHash().equals(hash)) throw CaseException.idempotencyKeyReused();
        // The stored response is released only if the actor still holds the required access.
        accessRecheck.run();
        return json.readValue(row.responseBody(), CaseView.class);
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
        var row = repo.findCase(caseId).orElseThrow(CaseException::notFound);
        if (!row.authorId().equals(actorId)) throw CaseException.notFound();
        if (!row.isDraft()) throw CaseException.alreadyConfirmed();
        return row;
    }

    private void requireAuthor(UUID actorId, UUID caseId) {
        var row = repo.findCase(caseId).orElseThrow(CaseException::notFound);
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
        var items = repo.findItems(row.id());
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
            row.sourceRevision(), repo.currentSourceText(row.id(), row.sourceRevision()),
            "ДЕМО-" + row.displayNumber(),
            new CaseReceipt(repo.confirmedAt(row.id()), instant(row.acceptedAt()), row.acceptedBy()),
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
        return runCommand(actorId, "accept", idempotencyKey, hash, 200, access, () -> {
            var row = loadVisible(actorId, caseId);
            requireDispatcherOfCase(actorId, caseId);
            if (!"CONFIRMED".equals(row.submissionStatus())) {
                throw CaseException.invalidTransition("Only a confirmed case can be accepted");
            }
            if (row.acceptedAt() != null) {
                throw CaseException.invalidTransition("The case is already accepted");
            }
            if (repo.bumpConfirmedCaseVersion(caseId, expectedCaseVersion) == 0) {
                return conflictOrReplay(actorId, "accept", idempotencyKey, hash, caseId, access, true);
            }
            // Re-accepting is not an error, but it must not overwrite who accepted first.
            repo.acceptCase(caseId, actorId);
            repo.insertAudit(caseId, actorId, "CASE_ACCEPTED",
                json.writeValueAsString(orderedMap("caseId", caseId)));
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
        if (updateDueAt != null && updateDueAt.isBefore(clock.instant())) {
            throw CaseException.invalidFields("updateDueAt must not be in the past");
        }
        String hash = hash("next-action", itemId, orderedMap(
            "expectedCaseVersion", expectedCaseVersion, "expectedItemVersion", expectedItemVersion,
            "text", text, "ownerId", ownerId,
            "updateDueAt", updateDueAt == null ? null : updateDueAt.toString()));
        Runnable access = () -> requireDispatcherOfItem(actorId, itemId);
        return runCommand(actorId, "next-action", idempotencyKey, hash, 200, access, () -> {
            var ctx = requireDispatcherOfItem(actorId, itemId);
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
                return conflictOrReplay(actorId, "next-action", idempotencyKey, hash, ctx.caseId(), access, false);
            }
            if (repo.setNextAction(itemId, expectedItemVersion, text, ownerId,
                updateDueAt == null ? null : Timestamp.from(updateDueAt)) == 0) {
                throw CaseException.itemVersionConflict();
            }
            repo.insertAudit(ctx.caseId(), actorId, "ITEM_NEXT_ACTION_SET",
                json.writeValueAsString(orderedMap("caseId", ctx.caseId(), "itemId", itemId,
                    "ownerId", ownerId)));
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
}
