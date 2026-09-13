package ru.koren.inference;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import static ru.koren.inference.InferenceContract.*;
import static ru.koren.inference.IntakeContract.*;

@Component
public class InferenceClient {
    private static final Logger log = LoggerFactory.getLogger(InferenceClient.class);
    private final RestClient client;

    public InferenceClient(@Value("${koren.inference-url}") String baseUrl) {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(3));
        client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public Response compare(Request request) {
        try {
            Response response = client.post().uri("/internal/v1/compare").body(request)
                    .retrieve().body(Response.class);
            return SuggestionValidator.validate(request, response);
        } catch (org.springframework.web.client.RestClientException | IllegalArgumentException exception) {
            // Log type only: exception messages may contain request or response texts.
            log.warn("Inference unavailable: {}", exception.getClass().getSimpleName());
            return new Response(request.caseId(), request.sourceRevision(), request.reportId(), "unavailable", Mode.MANUAL,
                    request.items().stream().map(item -> new Suggestion(item.itemId(), item.contentVersion(),
                            item.workRound(), Relation.INSUFFICIENT_INFORMATION, null, Reason.ANALYSIS_UNAVAILABLE)).toList());
        }
    }

    public ExtractResponse extract(ExtractRequest request) {
        try {
            return IntakeValidator.validate(request, client.post().uri("/internal/v1/extract-items").body(request)
                    .retrieve().body(ExtractResponse.class));
        } catch (org.springframework.web.client.RestClientException | IllegalArgumentException exception) {
            log.warn("Extraction unavailable: {}", exception.getClass().getSimpleName());
            return new ExtractResponse(request.caseId(), request.caseVersion(), request.sourceRevision(),
                    "unavailable", Mode.MANUAL, AnalysisStatus.NOT_ANALYZED, Reason.ANALYSIS_UNAVAILABLE, List.of());
        }
    }

    /** Empty means keep the editable manual form; no question may be sent from a failed response. */
    public Optional<ClarifyResponse> clarify(ClarifyRequest request) {
        try {
            return Optional.of(IntakeValidator.validate(request,
                    client.post().uri("/internal/v1/clarify-items").body(request).retrieve().body(ClarifyResponse.class)));
        } catch (org.springframework.web.client.RestClientException | IllegalArgumentException exception) {
            log.warn("Clarification unavailable: {}", exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public SimilarResponse similar(SimilarRequest request) {
        try {
            return IntakeValidator.validate(request, client.post().uri("/internal/v1/similar-cases").body(request)
                    .retrieve().body(SimilarResponse.class));
        } catch (org.springframework.web.client.RestClientException | IllegalArgumentException exception) {
            log.warn("Similarity unavailable: {}", exception.getClass().getSimpleName());
            return new SimilarResponse(request.query().caseId(), request.query().caseVersion(),
                    request.query().sourceRevision(), "unavailable", Mode.MANUAL, AnalysisStatus.NOT_ANALYZED,
                    Reason.ANALYSIS_UNAVAILABLE, "PROVIDED_CANDIDATES", request.candidates().stream()
                    .map(candidate -> new SimilarCaseResult(candidate.caseId(), candidate.caseVersion(),
                            candidate.sourceRevision(), SimilarityRelation.NOT_EVALUATED, null, List.of())).toList());
        }
    }
}
