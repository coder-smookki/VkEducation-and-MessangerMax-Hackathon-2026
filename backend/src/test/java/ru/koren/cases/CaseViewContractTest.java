package ru.koren.cases;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.koren.cases.application.CaseView;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The shared author-review examples must survive a Java round trip unchanged: one JSON for Java, React and tests. */
class CaseViewContractTest {
    private final JsonMapper json = JsonMapper.builder().build();
    @ParameterizedTest
    @ValueSource(strings = {"review-items-response.json", "confirm-items-response.json"})
    void sharedExampleRoundTripsThroughJavaCaseView(String example) throws Exception {
        String original = Files.readString(Path.of("../contracts/examples", example));
        CaseView view = json.readValue(original, CaseView.class);
        JsonNode roundTrip = json.readTree(json.writeValueAsString(view));
        assertEquals(json.readTree(original), roundTrip,
            example + ": a field was lost or changed when read by Java CaseView");
    }
}
