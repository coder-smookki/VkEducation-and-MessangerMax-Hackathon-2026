package ru.koren.inference;

import java.util.List;
import org.junit.jupiter.api.Test;
import ru.koren.demo.DemoController;
import static org.junit.jupiter.api.Assertions.*;
import static ru.koren.inference.InferenceContract.*;

class SuggestionValidatorTest {
    private final Request request = DemoController.sampleRequest();
    private Response response(List<Suggestion> suggestions) {
        return new Response(request.caseId(), request.sourceRevision(), request.reportId(), "rules-test", Mode.RULES, suggestions);
    }
    private Suggestion abstain(Item item) {
        return new Suggestion(item.itemId(), item.contentVersion(), item.workRound(),
                Relation.INSUFFICIENT_INFORMATION, null, Reason.MODEL_NOT_CONFIGURED);
    }

    @Test void missingAndDuplicateItemsAreRejected() {
        var first = abstain(request.items().getFirst());
        assertThrows(IllegalArgumentException.class, () -> SuggestionValidator.validate(request, response(List.of(first))));
        assertThrows(IllegalArgumentException.class, () -> SuggestionValidator.validate(request, response(List.of(first, first))));
    }
    @Test void staleSourceRevisionIsRejected() {
        var old = new Response(request.caseId(), 99, request.reportId(), "test", Mode.STUB,
                request.items().stream().map(this::abstain).toList());
        assertThrows(IllegalArgumentException.class, () -> SuggestionValidator.validate(request, old));
    }
    @Test void fabricatedEvidenceIsRejected() {
        var item = request.items().getFirst();
        var fake = new Suggestion(item.itemId(), 1, 1, Relation.REPORTED_DONE,
                new Evidence(0, 5, "дверь"), Reason.ANALYZED);
        assertThrows(IllegalArgumentException.class, () -> SuggestionValidator.validate(request,
                response(List.of(fake, abstain(request.items().getLast())))));
    }
    @Test void unicodeCodePointsWorkWithEmoji() {
        var unicode = new Request(request.caseId(), 1, request.reportId(), "💡 Свет работает", List.of(request.items().getFirst()));
        var item = unicode.items().getFirst();
        var suggestion = new Suggestion(item.itemId(), 1, 1, Relation.REPORTED_DONE,
                new Evidence(2, 15, "Свет работает"), Reason.ANALYZED);
        assertNotNull(SuggestionValidator.validate(unicode, response(List.of(suggestion))));
    }
    @Test void stubCannotClaimCompletedWork() {
        var item = request.items().getFirst();
        var malicious = new Suggestion(item.itemId(), 1, 1, Relation.REPORTED_DONE, null, Reason.MODEL_NOT_CONFIGURED);
        var result = new Response(request.caseId(), 1, request.reportId(), "stub", Mode.STUB,
                List.of(malicious, abstain(request.items().getLast())));
        assertThrows(IllegalArgumentException.class, () -> SuggestionValidator.validate(request, result));
    }
}
