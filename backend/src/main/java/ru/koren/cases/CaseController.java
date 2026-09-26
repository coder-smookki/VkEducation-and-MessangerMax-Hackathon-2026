package ru.koren.cases;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.koren.cases.application.CasePage;
import ru.koren.cases.application.CaseService;
import ru.koren.cases.application.CaseView;
import ru.koren.cases.application.ConfirmRequest;
import ru.koren.cases.application.CreateCaseRequest;
import ru.koren.cases.application.PatchItemsRequest;
import ru.koren.cases.application.PatchSourceRequest;
import ru.koren.cases.application.AcceptRequest;
import ru.koren.cases.application.HistoryPage;


/** Thin HTTP layer: the actor comes from the session, every rule stays in CaseService. */
@RestController
@RequestMapping("/api/v1/cases")
@Profile("!no-db")
public class CaseController {

    private final CaseService service;

    public CaseController(CaseService service) {
        this.service = service;
    }

    private static UUID actor(Authentication authentication) {
        return (UUID) authentication.getPrincipal();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CaseView create(Authentication authentication, @RequestBody CreateCaseRequest body,
                           @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return service.create(actor(authentication), body.houseId(), body.sourceText(), idempotencyKey);
    }

    @GetMapping("/{caseId}")
    public CaseView getCase(Authentication authentication, @PathVariable UUID caseId) {
        return service.getCase(actor(authentication), caseId);
    }

    @GetMapping
    public CasePage list(Authentication authentication, @RequestParam(required = false) UUID houseId,
                         @RequestParam(required = false) String queueState,
                         @RequestParam(required = false) String cursor,
                         @RequestParam(required = false) Integer limit) {
        return service.listCases(actor(authentication), houseId, queueState, cursor, limit);
    }

    @GetMapping("/{caseId}/history")
    public HistoryPage history(Authentication authentication, @PathVariable UUID caseId,
                               @RequestParam(required = false) String cursor,
                               @RequestParam(required = false) Integer limit) {
        return service.history(actor(authentication), caseId, cursor, limit);
    }

    @PatchMapping("/{caseId}/source")
    public CaseView patchSource(Authentication authentication, @PathVariable UUID caseId,
                                @RequestBody PatchSourceRequest body, @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return service.patchSource(actor(authentication), caseId, body.expectedCaseVersion(),
            body.sourceText(), idempotencyKey);
    }

    @PatchMapping("/{caseId}/items")
    public CaseView patchItems(Authentication authentication, @PathVariable UUID caseId,
                               @RequestBody PatchItemsRequest body, @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return service.patchItems(actor(authentication), caseId, body.expectedCaseVersion(),
            body.items(), idempotencyKey);
    }

    @PostMapping("/{caseId}/confirm")
    public CaseView confirm(Authentication authentication, @PathVariable UUID caseId,
                            @RequestBody ConfirmRequest body, @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return service.confirm(actor(authentication), caseId, body.expectedCaseVersion(),
            body.sourceRevision(), idempotencyKey);
    }

    @PostMapping("/{caseId}/acceptance")
    public CaseView accept(Authentication authentication, @PathVariable UUID caseId,
                           @RequestBody AcceptRequest body, @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return service.accept(actor(authentication), caseId, body.expectedCaseVersion(), idempotencyKey);
    }
}
