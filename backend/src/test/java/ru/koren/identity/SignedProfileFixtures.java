package ru.koren.identity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Synthetic vectors generated independently with Python hashlib/hmac, never by the validator under test. */
final class SignedProfileFixtures {
    private SignedProfileFixtures() { }

    static String initData(String name) {
        try (var stream = SignedProfileFixtures.class.getResourceAsStream("/max-profile-init-data.json")) {
            if (stream == null) throw new IllegalStateException("Missing signed profile fixtures");
            JsonNode fixtures = JsonMapper.builder().build().readTree(
                new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            return fixtures.get(name).asString();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read signed profile fixtures", failure);
        }
    }
}
