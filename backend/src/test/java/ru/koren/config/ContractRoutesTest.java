package ru.koren.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * S-08: the contract and the running application describe the same API. Every /api route Spring
 * actually registers must be in contracts/author-review.openapi.json as IMPLEMENTED, and every
 * IMPLEMENTED operation there must be a registered route. A schema alone is never proof that an
 * endpoint answers, and an endpoint missing from the contract is one the frontend has to guess.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=${DB_TEST_URL}", "spring.datasource.username=${DB_TEST_USER:koren}",
    "spring.datasource.password=${DB_TEST_PASSWORD}",
    "koren.max.bot-token=k1-test-bot-token:not-a-real-secret"
})
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class ContractRoutesTest {

    private static final Path CONTRACT = Path.of("../contracts/author-review.openapi.json");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    /** "{caseId}" and "{id}" name the same slot; only the shape of the path matters. */
    private static String normalize(String method, String path) {
        return method.toUpperCase() + " " + path.replaceAll("\\{[^}/]+}", "{}");
    }

    private Set<String> runtimeRoutes() {
        Set<String> routes = new TreeSet<>();
        mappings.getHandlerMethods().forEach((info, handler) -> {
            var patterns = info.getPathPatternsCondition() == null
                ? Set.<String>of() : info.getPathPatternsCondition().getPatternValues();
            for (String path : patterns) {
                if (!path.startsWith("/api/")) continue;
                info.getMethodsCondition().getMethods()
                    .forEach(method -> routes.add(normalize(method.name(), path)));
            }
        });
        return routes;
    }

    private Set<String> contractRoutes() throws Exception {
        JsonNode contract = JsonMapper.builder().build().readTree(Files.readString(CONTRACT));
        Set<String> routes = new TreeSet<>();
        for (var path : contract.get("paths").properties()) {
            for (var operation : path.getValue().properties()) {
                JsonNode status = operation.getValue().get("x-implementation-status");
                if (status != null && "IMPLEMENTED".equals(status.asString())) {
                    routes.add(normalize(operation.getKey(), path.getKey()));
                }
            }
        }
        return routes;
    }

    @Test
    void theContractAndTheRunningApiListTheSameRoutes() throws Exception {
        Set<String> runtime = runtimeRoutes();
        Set<String> contract = contractRoutes();

        Set<String> undocumented = new TreeSet<>(runtime);
        undocumented.removeAll(contract);
        Set<String> unrouted = new TreeSet<>(contract);
        unrouted.removeAll(runtime);

        assertEquals(Set.of(), undocumented, "routes that answer but are missing from the contract");
        assertEquals(Set.of(), unrouted, "IMPLEMENTED in the contract but not routed");
    }
}
