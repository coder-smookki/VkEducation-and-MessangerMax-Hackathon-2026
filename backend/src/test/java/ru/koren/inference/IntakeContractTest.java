package ru.koren.inference;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static ru.koren.inference.InferenceContract.*;
import static ru.koren.inference.IntakeContract.*;

class IntakeContractTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private String fixture(String name) throws Exception {
        return Files.readString(Path.of("../contracts/examples", name));
    }
    private <T> T fixture(String name, Class<T> type) throws Exception { return json.readValue(fixture(name), type); }

    @Test
    @EnabledIfEnvironmentVariable(named = "INFERENCE_TEST_URL", matches = ".+")
    void livePythonUsesTheSameFourContracts() throws Exception {
        var client = new InferenceClient(System.getenv("INFERENCE_TEST_URL"));
        assertEquals(fixture("compare-response.json", Response.class), client.compare(fixture("compare-request.json", Request.class)));
        assertEquals(fixture("extract-response.json", ExtractResponse.class), client.extract(fixture("extract-request.json", ExtractRequest.class)));
        assertEquals(fixture("clarify-response.json", ClarifyResponse.class), client.clarify(fixture("clarify-request.json", ClarifyRequest.class)).orElseThrow());
        assertEquals(fixture("similar-response.json", SimilarResponse.class), client.similar(fixture("similar-request.json", SimilarRequest.class)));
    }

    @Test void realHttpClientUsesTheSameJsonAsPythonForAllFourOperations() throws Exception {
        var names = Map.of("compare", "compare", "extract-items", "extract", "clarify-items", "clarify", "similar-cases", "similar");
        var bodies = new ConcurrentHashMap<String, String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/", exchange -> {
            String route = exchange.getRequestURI().getPath().substring("/internal/v1/".length());
            bodies.put(route, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = Files.readAllBytes(Path.of("../contracts/examples", names.get(route) + "-response.json"));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var client = new InferenceClient("http://127.0.0.1:" + server.getAddress().getPort());
            assertEquals(fixture("compare-response.json", Response.class), client.compare(fixture("compare-request.json", Request.class)));
            assertEquals(fixture("extract-response.json", ExtractResponse.class), client.extract(fixture("extract-request.json", ExtractRequest.class)));
            assertEquals(fixture("clarify-response.json", ClarifyResponse.class), client.clarify(fixture("clarify-request.json", ClarifyRequest.class)).orElseThrow());
            assertEquals(fixture("similar-response.json", SimilarResponse.class), client.similar(fixture("similar-request.json", SimilarRequest.class)));
            assertEquals(names.size(), bodies.size());
            for (var entry : names.entrySet())
                assertEquals(json.readTree(fixture(entry.getValue() + "-request.json")), json.readTree(bodies.get(entry.getKey())));
        } finally { server.stop(0); }
    }

    @Test void unavailableServicePreservesManualDraftAndEveryCandidate() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { exchange.sendResponseHeaders(503, -1); exchange.close(); });
        server.start();
        try {
            var client = new InferenceClient("http://127.0.0.1:" + server.getAddress().getPort());
            var extract = client.extract(fixture("extract-request.json", ExtractRequest.class));
            assertEquals(Mode.MANUAL, extract.mode());
            assertTrue(extract.proposals().isEmpty());
            assertTrue(client.clarify(fixture("clarify-request.json", ClarifyRequest.class)).isEmpty());
            var request = fixture("similar-request.json", SimilarRequest.class);
            var result = client.similar(request);
            assertEquals(Mode.MANUAL, result.mode());
            assertEquals(request.candidates().getFirst().caseId(), result.results().getFirst().caseId());
            assertEquals(SimilarityRelation.NOT_EVALUATED, result.results().getFirst().relation());
            assertNull(result.results().getFirst().score());
            assertNotNull(IntakeValidator.validate(request, result));
        } finally { server.stop(0); }
    }

    @Test void proposalsAreAdvisoryAndEvidenceMustBeExactIncludingEmoji() throws Exception {
        var request = fixture("extract-request.json", ExtractRequest.class);
        var response = fixture("extract-response.synthetic-proposals.json", ExtractResponse.class);
        assertNotNull(IntakeValidator.validate(request, response));
        var unicode = new ExtractRequest(request.caseId(), request.caseVersion(), request.sourceRevision(), "💡 Свет не горит");
        var item = new ProposedItem(1, Category.LIGHTING, null, "Не горит свет", new Evidence(2, 15, "Свет не горит"));
        // Code-point end is 15 for this exact text; UTF-16 end would be 16.
        var valid = new ExtractResponse(unicode.caseId(), unicode.caseVersion(), unicode.sourceRevision(), "synthetic",
                Mode.RULES, AnalysisStatus.ANALYZED, Reason.ANALYZED, List.of(item));
        assertNotNull(IntakeValidator.validate(unicode, valid));
        var forged = new ExtractResponse(request.caseId(), request.caseVersion(), request.sourceRevision(),
                "stub", Mode.STUB, AnalysisStatus.NOT_ANALYZED, Reason.MODEL_NOT_CONFIGURED, response.proposals());
        assertThrows(IllegalArgumentException.class, () -> IntakeValidator.validate(request, forged));
        var stale = new ExtractResponse(request.caseId(), request.caseVersion() + 1, request.sourceRevision(),
                response.modelVersion(), response.mode(), response.status(), response.reason(), response.proposals());
        assertThrows(IllegalArgumentException.class, () -> IntakeValidator.validate(request, stale));
        assertThrows(IllegalArgumentException.class, () -> IntakeValidator.validate(request, valid));
    }

    @Test void onlyAuthorMayReceiveQuestionsAndReadinessMustMatchMissingFields() throws Exception {
        var request = fixture("clarify-request.json", ClarifyRequest.class);
        var result = fixture("clarify-response.json", ClarifyResponse.class);
        assertNotNull(IntakeValidator.validate(request, result));
        var neighbors = new ClarifyResponse(result.caseId(), result.caseVersion(), result.sourceRevision(),
                result.modelVersion(), result.mode(), "NEIGHBORS", result.items());
        assertThrows(IllegalArgumentException.class, () -> IntakeValidator.validate(request, neighbors));
        var missing = new ClarifyResponse(result.caseId(), result.caseVersion(), result.sourceRevision(),
                result.modelVersion(), result.mode(), "AUTHOR", List.of(result.items().getFirst()));
        assertThrows(IllegalArgumentException.class, () -> IntakeValidator.validate(request, missing));
        var wrong = new ItemClarification(request.items().getFirst().proposalId(), Readiness.READY_FOR_REVIEW, List.of());
        var falseReady = new ClarifyResponse(result.caseId(), result.caseVersion(), result.sourceRevision(),
                result.modelVersion(), result.mode(), "AUTHOR", List.of(wrong, result.items().getLast()));
        assertThrows(IllegalArgumentException.class, () -> IntakeValidator.validate(request, falseReady));
    }

    @Test void onlyConfirmedContentFromOneHouseEntersSimilarity() throws Exception {
        var request = fixture("similar-request.json", SimilarRequest.class);
        var query = request.query();
        assertThrows(IllegalArgumentException.class, () -> new ConfirmedCaseContext(query.caseId(), query.caseVersion(),
                query.sourceRevision(), query.houseId(), "DRAFT", query.sourceText(), query.items()));
        assertThrows(IllegalArgumentException.class, () -> new SimilarRequest(query, List.of(query)));
        var candidate = request.candidates().getFirst();
        var foreign = new ConfirmedCaseContext(candidate.caseId(), candidate.caseVersion(), candidate.sourceRevision(),
                candidate.caseId(), "CONFIRMED", candidate.sourceText(), candidate.items());
        assertThrows(IllegalArgumentException.class, () -> new SimilarRequest(query, List.of(foreign)));
        assertThrows(IllegalArgumentException.class, () -> new SimilarRequest(query, List.of(candidate, candidate)));
        var comparison = query.comparison(java.util.UUID.randomUUID(), "Лампу заменили");
        assertEquals(query.items(), comparison.items());
        assertEquals(query.sourceRevision(), comparison.sourceRevision());
    }

    @Test void similarityCannotLoseCandidatesInventMatchesOrHideUnknownAsZero() throws Exception {
        var request = fixture("similar-request.json", SimilarRequest.class);
        var response = fixture("similar-response.json", SimilarResponse.class);
        assertNotNull(IntakeValidator.validate(request, response));
        var candidate = response.results().getFirst();
        var zero = new SimilarCaseResult(candidate.caseId(), candidate.caseVersion(), candidate.sourceRevision(),
                SimilarityRelation.NOT_EVALUATED, 0.0, List.of());
        var forged = new SimilarResponse(response.caseId(), response.caseVersion(), response.sourceRevision(),
                response.modelVersion(), response.mode(), response.status(), response.reason(), response.scope(), List.of(zero));
        assertThrows(IllegalArgumentException.class, () -> IntakeValidator.validate(request, forged));
        var missing = new SimilarResponse(response.caseId(), response.caseVersion(), response.sourceRevision(),
                response.modelVersion(), response.mode(), response.status(), response.reason(), response.scope(), List.of());
        assertThrows(IllegalArgumentException.class, () -> IntakeValidator.validate(request, missing));
        var pair = new ItemMatch(request.query().items().getFirst().itemId(), request.query().items().getFirst().itemId());
        var match = new SimilarCaseResult(candidate.caseId(), candidate.caseVersion(), candidate.sourceRevision(),
                SimilarityRelation.POSSIBLE_DUPLICATE, 0.9, List.of(pair));
        var foreign = new SimilarResponse(response.caseId(), response.caseVersion(), response.sourceRevision(), "synthetic",
                Mode.RULES, AnalysisStatus.ANALYZED, Reason.ANALYZED, response.scope(), List.of(match));
        assertThrows(IllegalArgumentException.class, () -> IntakeValidator.validate(request, foreign));
    }
}
