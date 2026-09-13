package ru.koren.inference;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
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

    public void publishCreated(ConfirmedCaseContext snapshot) {
        publish("CASE_CREATED", snapshot);
    }

    public void publishUpdated(ConfirmedCaseContext snapshot) {
        publish("CASE_UPDATED", snapshot);
    }

    private void publish(String eventType, ConfirmedCaseContext snapshot) {
        var event = new CaseUpdatedEvent(UUID.randomUUID(), eventType, 1, snapshot);
        kafka.send(topic, snapshot.caseId().toString(), event);
    }
}
