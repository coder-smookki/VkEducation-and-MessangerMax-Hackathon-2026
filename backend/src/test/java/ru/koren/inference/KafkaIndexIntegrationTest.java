package ru.koren.inference;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static ru.koren.inference.InferenceContract.*;
import static ru.koren.inference.IntakeContract.*;

/** Real Java producer -> Kafka -> Python -> persistent Chroma -> typed HTTP response, using synthetic texts. */
@EnabledIfEnvironmentVariable(named="KAFKA_TEST_BOOTSTRAP", matches=".+")
class KafkaIndexIntegrationTest {
    @Test void actualKafkaPublisherIndexesTheDoorAndOldDeliveryCannotRegressIt() throws Exception {
        String broker = System.getenv("KAFKA_TEST_BOOTSTRAP");
        String topic = "koren.case-updates";
        var factory = new DefaultKafkaProducerFactory<String, CaseUpdatedEvent>(Map.of(
                "bootstrap.servers", broker, "key.serializer", StringSerializer.class,
                "value.serializer", JacksonJsonSerializer.class, "spring.json.add.type.headers", false));
        var template = new KafkaTemplate<>(factory);
        var client = new InferenceClient(java.util.Objects.requireNonNull(System.getenv("INFERENCE_TEST_URL")));
        try (var admin = Admin.create(Map.of("bootstrap.servers", broker))) {
            var base = JsonMapper.builder().build().readValue(Files.readString(Path.of("../contracts/examples/similar-request.json")), SimilarRequest.class);
            UUID house = UUID.randomUUID();
            var query = new ConfirmedCaseContext(UUID.randomUUID(), 1, 1, house, "CONFIRMED", base.query().sourceText(),
                    base.query().items().stream().map(item -> new Item(UUID.randomUUID(),1,0,item.category(),item.location(),item.symptom())).toList());
            var door = query.items().get(1);
            var candidate = new ConfirmedCaseContext(UUID.randomUUID(),7,1,house,"CONFIRMED","Synthetic: дверь не закрывается",
                    List.of(new Item(UUID.randomUUID(),1,0,door.category(),door.location(),door.symptom())));
            var request = new SimilarRequest(query,List.of(candidate),5);
            assertEquals(AnalysisStatus.NOT_ANALYZED, client.similar(request).status());
            var publisher = new CaseEventPublisher(template,topic);
            var sent = publisher.publishCreated(candidate).get(30,TimeUnit.SECONDS);
            awaitCommit(admin,topic,sent.getRecordMetadata().partition(),sent.getRecordMetadata().offset());
            var response = client.similar(request);
            assertEquals(AnalysisStatus.ANALYZED,response.status());
            assertEquals(SimilarityRelation.RELATED,response.results().getFirst().relation());
            assertEquals(List.of(new ItemMatch(door.itemId(),candidate.items().getFirst().itemId())),response.results().getFirst().matchedItems());
            var old = new ConfirmedCaseContext(candidate.caseId(),6,1,house,"CONFIRMED",candidate.sourceText(),candidate.items());
            var repeated = publisher.publishUpdated(old).get(30,TimeUnit.SECONDS);
            awaitCommit(admin,topic,repeated.getRecordMetadata().partition(),repeated.getRecordMetadata().offset());
            assertEquals(7,client.similar(request).results().getFirst().caseVersion());
        } finally {
            template.destroy();
            factory.destroy();
        }
    }

    private void awaitCommit(Admin admin, String topic, int partition, long offset) throws Exception {
        long deadline = System.nanoTime()+Duration.ofSeconds(120).toNanos();
        while (System.nanoTime()<deadline) {
            var committed = admin.listConsumerGroupOffsets("koren-ml-indexer")
                    .partitionsToOffsetAndMetadata().get(5,TimeUnit.SECONDS).get(new TopicPartition(topic,partition));
            if (committed != null && committed.offset()>offset) return;
            Thread.sleep(200);
        }
        fail("ML did not acknowledge the indexed event within 120 seconds");
    }
}
