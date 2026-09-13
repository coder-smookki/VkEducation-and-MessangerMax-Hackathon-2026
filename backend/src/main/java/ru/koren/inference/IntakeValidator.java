package ru.koren.inference;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import static ru.koren.inference.InferenceContract.*;
import static ru.koren.inference.IntakeContract.*;

/** Checks request/response relationships that JSON Schema alone cannot express. No database writes. */
public final class IntakeValidator {
    private IntakeValidator() {}

    public static ExtractResponse validate(ExtractRequest request, ExtractResponse response) {
        require(response != null, "Missing extraction");
        sameReference(request.caseId(), request.caseVersion(), request.sourceRevision(),
                response.caseId(), response.caseVersion(), response.sourceRevision());
        outcome(response.modelVersion(), response.mode(), response.status(), response.reason());
        require(response.proposals() != null && response.proposals().size() <= 20, "Invalid proposals");
        if (response.status() == AnalysisStatus.NOT_ANALYZED) require(response.proposals().isEmpty(), "Stub invented items");
        var ids = new HashSet<Integer>();
        for (var proposal : response.proposals()) {
            require(proposal != null && ids.add(proposal.proposalId()), "Duplicate or null proposal");
            new DraftItem(proposal.proposalId(), proposal.category(), proposal.location(), proposal.symptom());
            Evidence evidence = proposal.evidence();
            require(evidence != null && evidence.start() >= 0 && evidence.end() > evidence.start()
                    && evidence.end() <= request.sourceText().codePointCount(0, request.sourceText().length()),
                    "Invalid extraction evidence");
            String actual = request.sourceText().substring(request.sourceText().offsetByCodePoints(0, evidence.start()),
                    request.sourceText().offsetByCodePoints(0, evidence.end()));
            require(actual.equals(evidence.quote()), "Fabricated extraction evidence");
        }
        return response;
    }

    public static ClarifyResponse validate(ClarifyRequest request, ClarifyResponse response) {
        require(response != null, "Missing clarification");
        sameReference(request.caseId(), request.caseVersion(), request.sourceRevision(),
                response.caseId(), response.caseVersion(), response.sourceRevision());
        require("AUTHOR".equals(response.recipient()), "Clarification must address only the author");
        require(response.mode() == Mode.RULES && "required-fields-v1".equals(response.modelVersion()), "Unknown question policy");
        require(response.items() != null && response.items().size() == request.items().size(), "Missing draft item");
        Map<Integer, DraftItem> expected = new HashMap<>();
        request.items().forEach(item -> expected.put(item.proposalId(), item));
        var seen = new HashSet<Integer>();
        for (var item : response.items()) {
            require(item != null && seen.add(item.proposalId()), "Duplicate or null clarification");
            DraftItem source = expected.get(item.proposalId());
            require(source != null && item.questions() != null && item.questions().size() <= 3, "Foreign draft item");
            var missing = new HashSet<MissingField>();
            if (source.category() == null) missing.add(MissingField.category);
            if (source.location() == null) missing.add(MissingField.location);
            if (source.symptom() == null) missing.add(MissingField.symptom);
            require(item.readiness() == (missing.isEmpty() ? Readiness.READY_FOR_REVIEW : Readiness.NEEDS_DETAILS),
                    "Invalid readiness");
            var fields = new HashSet<MissingField>();
            for (var question : item.questions()) {
                require(question != null && question.field() != null && fields.add(question.field()), "Duplicate question");
                text(question.text(), 300);
            }
            require(fields.equals(missing), "Questions must describe only missing fields");
        }
        return response;
    }

    public static SimilarResponse validate(SimilarRequest request, SimilarResponse response) {
        require(response != null, "Missing similarity");
        sameReference(request.query().caseId(), request.query().caseVersion(), request.query().sourceRevision(),
                response.caseId(), response.caseVersion(), response.sourceRevision());
        outcome(response.modelVersion(), response.mode(), response.status(), response.reason());
        require("CHROMA".equals(response.scope()), "Search scope changed");
        require(response.results() != null && response.results().size() <= request.limit(), "Too many results");
        Map<UUID, ConfirmedCaseContext> candidates = new HashMap<>();
        request.candidates().forEach(candidate -> candidates.put(candidate.caseId(), candidate));
        var queryItems = new HashSet<UUID>();
        request.query().items().forEach(item -> queryItems.add(item.itemId()));
        var seen = new HashSet<UUID>();
        for (var result : response.results()) {
            require(result != null && seen.add(result.caseId()), "Duplicate or null candidate");
            reference(result.caseId(), result.caseVersion(), result.sourceRevision());
            var candidate = candidates.get(result.caseId());
            require(candidate != null, "Foreign candidate");
            sameReference(candidate.caseId(), candidate.caseVersion(), candidate.sourceRevision(),
                    result.caseId(), result.caseVersion(), result.sourceRevision());
            require(result.relation() != null && result.matchedItems() != null && result.matchedItems().size() <= 20,
                    "Invalid similarity result");
            if (response.status() == AnalysisStatus.NOT_ANALYZED)
                require(result.relation() == SimilarityRelation.NOT_EVALUATED, "Stub cannot classify cases");
            if (result.relation() == SimilarityRelation.NOT_EVALUATED) {
                require(result.score() == null && result.matchedItems().isEmpty(), "Unknown is not zero similarity");
            } else {
                require(result.score() != null && Double.isFinite(result.score()) && result.score() >= 0
                        && result.score() <= 1, "Invalid similarity score");
                boolean related = result.relation() != SimilarityRelation.DIFFERENT;
                require(related == !result.matchedItems().isEmpty(), "Missing or contradictory item matches");
            }
            var matchedQuery = new HashSet<UUID>();
            var matchedCandidate = new HashSet<UUID>();
            var candidateItems = new HashSet<UUID>();
            candidate.items().forEach(item -> candidateItems.add(item.itemId()));
            for (var pair : result.matchedItems()) {
                require(pair != null && queryItems.contains(pair.queryItemId())
                        && candidateItems.contains(pair.candidateItemId()) && matchedQuery.add(pair.queryItemId())
                        && matchedCandidate.add(pair.candidateItemId()), "Foreign or reused item match");
            }
            if (result.relation() == SimilarityRelation.POSSIBLE_DUPLICATE)
                require(matchedQuery.equals(queryItems) && matchedCandidate.equals(candidateItems),
                        "A partial item match is not a duplicate case");
        }
        return response;
    }

    private static void sameReference(UUID expectedId, int expectedCaseVersion, int expectedSource,
                                       UUID id, int caseVersion, int source) {
        require(Objects.equals(expectedId, id) && expectedCaseVersion == caseVersion && expectedSource == source,
                "Stale or foreign case reference");
    }
    private static void outcome(String model, Mode mode, AnalysisStatus status, Reason reason) {
        text(model, 200);
        require(mode != null && status != null && reason != null, "Missing analysis outcome");
        if (mode == Mode.STUB || mode == Mode.MANUAL) {
            require(status == AnalysisStatus.NOT_ANALYZED
                    && reason == (mode == Mode.STUB ? Reason.MODEL_NOT_CONFIGURED : Reason.ANALYSIS_UNAVAILABLE),
                    "Fallback must abstain");
        } else require(status == AnalysisStatus.ANALYZED && reason == Reason.ANALYZED, "Invalid analyzed outcome");
    }
}
