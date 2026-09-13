package ru.koren.inference;

import java.util.UUID;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Bean;
import org.apache.kafka.clients.admin.NewTopic;
import static ru.koren.inference.IntakeContract.ConfirmedCaseContext;

/** Publishes an authorized case snapshot for the ML read index after Java changes product state. */
@Component
@ConditionalOnProperty(name = "koren.kafka.enabled", havingValue = "true")
public class CaseEventPublisher {
    private final KafkaTemplate<String, CaseUpdatedEvent> kafka;
    private final String topic;

    public CaseEventPublisher(KafkaTemplate<String, CaseUpdatedEvent> kafka,
                              @Value("${koren.kafka.case-updates-topic}") String topic) {
        this.kafka = kafka;
        this.topic = topic;
    }

    @Bean
    NewTopic caseUpdatesTopic() {
        // Retain history in the MVP: Kafka's last offset is not necessarily the highest caseVersion.
        return new NewTopic(topic, 1, (short) 1).configs(Map.of(
                "cleanup.policy", "delete", "retention.ms", "-1", "retention.bytes", "-1"));
    }

    public CompletableFuture<SendResult<String, CaseUpdatedEvent>> publishCreated(ConfirmedCaseContext snapshot) {
        return publish("CASE_CREATED", snapshot);
    }

    public CompletableFuture<SendResult<String, CaseUpdatedEvent>> publishUpdated(ConfirmedCaseContext snapshot) {
        return publish("CASE_UPDATED", snapshot);
    }

    private CompletableFuture<SendResult<String, CaseUpdatedEvent>> publish(String eventType, ConfirmedCaseContext snapshot) {
        var event = new CaseUpdatedEvent(UUID.randomUUID(), eventType, 1, snapshot);
        return kafka.send(topic, snapshot.caseId().toString(), event);
    }
}
