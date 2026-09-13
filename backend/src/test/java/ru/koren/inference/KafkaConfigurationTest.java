package ru.koren.inference;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"koren.kafka.enabled=true", "spring.kafka.admin.auto-create=false"})
@ActiveProfiles("local")
class KafkaConfigurationTest {
    @Autowired CaseEventPublisher publisher;
    @Autowired KafkaTemplate<?, ?> kafka;

    @Test void enabledPublisherStartsAndUsesThePythonEventShape() throws Exception {
        assertNotNull(publisher);
        assertNotNull(kafka);
        var json = JsonMapper.builder().build();
        var request = json.readValue(Files.readString(Path.of("../contracts/examples/similar-request.json")),
                IntakeContract.SimilarRequest.class);
        var event = new CaseUpdatedEvent(java.util.UUID.randomUUID(), "CASE_UPDATED", 1, request.query());
        try (var serializer = new JacksonJsonSerializer<CaseUpdatedEvent>()) {
            var wire = json.readTree(serializer.serialize("test", event));
            assertEquals(request.query().caseId().toString(), wire.get("case").get("caseId").asString());
            assertFalse(wire.has("caseSnapshot"));
            assertEquals(1, wire.get("schemaVersion").asInt());
        }
    }

    @Test void publicationFailureRemainsVisibleToTheCaller() throws Exception {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, CaseUpdatedEvent> template = mock(KafkaTemplate.class);
        var failure = new IllegalStateException("Synthetic broker outage");
        when(template.send(eq("test"), anyString(), any(CaseUpdatedEvent.class)))
                .thenReturn(java.util.concurrent.CompletableFuture.failedFuture(failure));
        var request = JsonMapper.builder().build().readValue(
                Files.readString(Path.of("../contracts/examples/similar-request.json")), IntakeContract.SimilarRequest.class);
        var failed = new CaseEventPublisher(template, "test").publishUpdated(request.query());
        var error = assertThrows(java.util.concurrent.CompletionException.class, failed::join);
        assertSame(failure, error.getCause());
        verify(template).send(eq("test"), eq(request.query().caseId().toString()), any(CaseUpdatedEvent.class));
    }
}
