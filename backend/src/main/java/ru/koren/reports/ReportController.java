package ru.koren.reports;

import java.net.URI;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.koren.cases.application.CaseService;
import ru.koren.reports.application.CreateReportRequest;
import ru.koren.reports.application.CreateReportResponse;
import ru.koren.reports.application.WorkReportView;

/** Work reports; every rule stays in CaseService, the actor comes from the session. */
@RestController
@RequestMapping("/api/v1")
@Profile("!no-db")
public class ReportController {

    private final CaseService service;

    public ReportController(CaseService service) {
        this.service = service;
    }

    private static UUID actor(Authentication authentication) {
        return (UUID) authentication.getPrincipal();
    }

    @PostMapping("/cases/{caseId}/reports")
    public ResponseEntity<CreateReportResponse> create(Authentication authentication,
                                                       @PathVariable UUID caseId,
                                                       @RequestBody CreateReportRequest body,
                                                       @RequestHeader("Idempotency-Key") String idempotencyKey) {
        var created = service.createReport(actor(authentication), caseId, body.expectedCaseVersion(),
            body.reportText(), body.targetItemIds(), idempotencyKey);
        return ResponseEntity.created(URI.create("/api/v1/reports/" + created.report().reportId()))
            .body(created);
    }

    @GetMapping("/reports/{reportId}")
    public WorkReportView get(Authentication authentication, @PathVariable UUID reportId) {
        return service.getReport(actor(authentication), reportId);
    }
}
