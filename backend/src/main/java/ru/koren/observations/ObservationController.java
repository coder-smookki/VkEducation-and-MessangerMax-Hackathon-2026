package ru.koren.observations;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.koren.cases.application.CaseService;
import ru.koren.cases.application.CaseView;
import ru.koren.observations.application.ObservationRequest;

/** The author's answer about one item. Only the author, only the current round. */
@RestController
@RequestMapping("/api/v1/items")
@Profile("!no-db")
public class ObservationController {

    private final CaseService service;

    public ObservationController(CaseService service) {
        this.service = service;
    }

    @PostMapping("/{itemId}/observations")
    public CaseView observe(Authentication authentication, @PathVariable UUID itemId,
                            @RequestBody ObservationRequest body,
                            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return service.observe((UUID) authentication.getPrincipal(), itemId, body.expectedCaseVersion(),
            body.expectedItemVersion(), body.contentVersion(), body.workRound(),
            body.value(), body.source(), idempotencyKey);
    }
}
