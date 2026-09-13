package ru.koren.inference;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import static ru.koren.inference.InferenceContract.*;

/** Mirrors intake_contracts.py. A proposalId is a draft slot, never a persisted itemId. */
public final class IntakeContract {
    private IntakeContract() {}
    public enum AnalysisStatus { NOT_ANALYZED, ANALYZED }
    public enum Readiness { NEEDS_DETAILS, READY_FOR_REVIEW }
    public enum MissingField { category, location, symptom }
    public enum SimilarityRelation { POSSIBLE_DUPLICATE, RELATED, DIFFERENT, NOT_EVALUATED }

    public record DraftItem(int proposalId, Category category, String location, String symptom) {
        public DraftItem {
            require(proposalId >= 1 && proposalId <= 20, "Invalid proposalId");
            if (location != null) text(location, 300);
            if (symptom != null) text(symptom, 1000);
        }
    }
    public record ProposedItem(int proposalId, Category category, String location, String symptom, Evidence evidence) {}
    public record ExtractRequest(UUID caseId, int caseVersion, int sourceRevision, String sourceText) {
        public ExtractRequest {
            reference(caseId, caseVersion, sourceRevision);
            text(sourceText, 5000);
        }
    }
    public record ExtractResponse(UUID caseId, int caseVersion, int sourceRevision, String modelVersion,
                                  Mode mode, AnalysisStatus status, Reason reason, List<ProposedItem> proposals) {}

    public record ClarifyRequest(UUID caseId, int caseVersion, int sourceRevision, List<DraftItem> items) {
        public ClarifyRequest {
            reference(caseId, caseVersion, sourceRevision);
            items = List.copyOf(items);
            require(!items.isEmpty() && items.size() <= 20, "Invalid draft size");
            require(items.stream().map(DraftItem::proposalId).distinct().count() == items.size(), "Duplicate proposal");
        }
    }
    public record AuthorQuestion(MissingField field, String text) {}
    public record ItemClarification(int proposalId, Readiness readiness, List<AuthorQuestion> questions) {}
    public record ClarifyResponse(UUID caseId, int caseVersion, int sourceRevision, String modelVersion,
                                  Mode mode, String recipient, List<ItemClarification> items) {}

    /** Construct only from a server-authorized, consistent database snapshot after author confirmation. */
    public record ConfirmedCaseContext(UUID caseId, int caseVersion, int sourceRevision, UUID houseId,
                                       String submissionStatus, String sourceText, List<Item> items) {
        public ConfirmedCaseContext {
            reference(caseId, caseVersion, sourceRevision);
            Objects.requireNonNull(houseId);
            require("CONFIRMED".equals(submissionStatus), "Unconfirmed content is not model input");
            text(sourceText, 5000);
            items = List.copyOf(items);
            require(!items.isEmpty() && items.size() <= 20, "Invalid confirmed item count");
            var ids = new HashSet<UUID>();
            for (Item item : items) {
                require(item.itemId() != null && ids.add(item.itemId()), "Duplicate or missing itemId");
                require(item.contentVersion() >= 1 && item.workRound() >= 0 && item.category() != null, "Invalid item");
                text(item.location(), 300);
                text(item.symptom(), 1000);
            }
        }

        public Request comparison(UUID reportId, String reportText) {
            Objects.requireNonNull(reportId);
            text(reportText, 5000);
            return new Request(caseId, sourceRevision, reportId, reportText, items);
        }
    }

    public record SimilarRequest(ConfirmedCaseContext query, List<ConfirmedCaseContext> candidates, int limit) {
        public SimilarRequest {
            Objects.requireNonNull(query);
            candidates = List.copyOf(candidates);
            require(candidates.size() <= 20, "Too many candidates");
            var cases = new HashSet<UUID>();
            cases.add(query.caseId());
            var items = new HashSet<UUID>();
            query.items().forEach(item -> items.add(item.itemId()));
            for (var candidate : candidates) {
                require(cases.add(candidate.caseId()), "Duplicate or self candidate");
                require(query.houseId().equals(candidate.houseId()), "Foreign house candidate");
                candidate.items().forEach(item -> require(items.add(item.itemId()), "Item shared by two cases"));
            }
            require(limit >= 1 && limit <= 20, "Invalid similarity limit");
        }
    }
    public record ItemMatch(UUID queryItemId, UUID candidateItemId) {}
    public record SimilarCaseResult(UUID caseId, int caseVersion, int sourceRevision,
                                    SimilarityRelation relation, Double score, List<ItemMatch> matchedItems) {}
    public record SimilarResponse(UUID caseId, int caseVersion, int sourceRevision, String modelVersion,
                                  Mode mode, AnalysisStatus status, Reason reason, String scope,
                                  List<SimilarCaseResult> results) {}

    static void reference(UUID caseId, int caseVersion, int sourceRevision) {
        require(caseId != null && caseVersion >= 1 && sourceRevision >= 1, "Invalid case reference");
    }
    static void text(String value, int max) {
        require(value != null && value.codePoints().anyMatch(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c))
                && value.codePointCount(0, value.length()) <= max, "Missing or oversized text");
    }
    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
