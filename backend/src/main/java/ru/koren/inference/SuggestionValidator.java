package ru.koren.inference;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import static ru.koren.inference.InferenceContract.*;

/** Validate the external inference boundary before showing an advisory result. */
public final class SuggestionValidator {
    private SuggestionValidator() {}

    public static Response validate(Request request, Response response) {
        require(response != null, "Missing response");
        require(Objects.equals(request.caseId(), response.caseId())
                && Objects.equals(request.reportId(), response.reportId())
                && request.sourceRevision() == response.sourceRevision(), "Stale or foreign response");
        require(response.modelVersion() != null && !response.modelVersion().isBlank()
                && response.modelVersion().length() <= 200 && response.mode() != null, "Missing model identity");
        require(response.suggestions() != null && response.suggestions().size() == request.items().size(), "Missing item");
        Map<UUID, Item> expected = new HashMap<>();
        request.items().forEach(item -> require(expected.put(item.itemId(), item) == null, "Duplicate input item"));
        var seen = new HashSet<UUID>();
        for (Suggestion suggestion : response.suggestions()) {
            require(suggestion != null && seen.add(suggestion.itemId()), "Duplicate or null suggestion");
            Item item = expected.get(suggestion.itemId());
            require(item != null && item.contentVersion() == suggestion.contentVersion()
                    && item.workRound() == suggestion.workRound(), "Foreign or stale item");
            require(suggestion.relation() != null && suggestion.reason() != null, "Missing relation or reason");
            if (response.mode() == Mode.STUB || response.mode() == Mode.MANUAL) {
                require(suggestion.relation() == Relation.INSUFFICIENT_INFORMATION && suggestion.evidence() == null,
                        "Fallback must abstain");
            }
            Evidence evidence = suggestion.evidence();
            if (evidence == null) {
                require(suggestion.relation() == Relation.INSUFFICIENT_INFORMATION, "Missing evidence");
            } else {
                String text = request.reportText();
                require(evidence.start() >= 0 && evidence.end() > evidence.start()
                        && evidence.end() <= text.codePointCount(0, text.length()), "Invalid evidence span");
                String actual = text.substring(text.offsetByCodePoints(0, evidence.start()),
                        text.offsetByCodePoints(0, evidence.end()));
                require(actual.equals(evidence.quote()), "Evidence does not match source");
            }
        }
        return response;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
