package ru.koren.inference;

import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import static ru.koren.inference.InferenceContract.*;

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
}
