package ru.koren.cases;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.koren.cases.application.CaseService;
import ru.koren.cases.application.CaseView;
import ru.koren.cases.application.NextActionRequest;
import org.springframework.web.bind.annotation.PostMapping;
import ru.koren.cases.application.DecisionRequest;

/** Per-item commands; the case that owns the item decides who may run them. */
@RestController
@RequestMapping("/api/v1/items")
@Profile("!no-db")
public class ItemController {

    private final CaseService service;

    public ItemController(CaseService service) {
        this.service = service;
    }

    private static UUID actor(Authentication authentication) {
        return (UUID) authentication.getPrincipal();
    }

    @PutMapping("/{itemId}/next-action")
    public CaseView setNextAction(Authentication authentication, @PathVariable UUID itemId,
                                  @RequestBody NextActionRequest body,
                                  @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return service.setNextAction(actor(authentication), itemId, body.expectedCaseVersion(),
            body.expectedItemVersion(), body.text(), body.ownerId(), body.updateDueAt(), idempotencyKey);
    }

    @PostMapping("/{itemId}/administrative-decisions")
    public CaseView decide(Authentication authentication, @PathVariable UUID itemId,
                           @RequestBody DecisionRequest body,
                           @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return service.decide(actor(authentication), itemId, body.expectedCaseVersion(),
            body.expectedItemVersion(), body.action(), body.reason(), idempotencyKey);
    }
}
