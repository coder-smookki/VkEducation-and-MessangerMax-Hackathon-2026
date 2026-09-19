package ru.koren.identity;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import ru.koren.identity.InvalidInitDataException.Reason;

/**
 * Validates raw {@code window.WebApp.initData} per the official MAX algorithm
 * (https://dev.max.ru/docs/webapps/validation):
 * secret = HMAC-SHA256(key "WebAppData", bot token),
 * hash = hex HMAC-SHA256(secret, data-check-string),
 * data-check-string = all pairs except hash, keys sorted a-z, "key=<url-decoded value>" joined by '\n'.
 * The bot token never appears in logs, exceptions or results.
 */
public final class MaxInitDataValidator {

    // Freshness policy is our own server rule, not a MAX requirement.
    static final Duration MAX_AGE = Duration.ofMinutes(5);
    static final Duration MAX_FUTURE_SKEW = Duration.ofSeconds(60);

    private static final int HASH_BYTES = 32;

    private final byte[] secretKey;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    public MaxInitDataValidator(String botToken, Clock clock) {
        if (botToken == null || botToken.isBlank()) {
            throw new IllegalArgumentException("bot token must not be blank");
        }
        this.secretKey = hmacSha256(
                "WebAppData".getBytes(StandardCharsets.UTF_8), botToken.getBytes(StandardCharsets.UTF_8));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public MaxVerifiedUser validate(String rawInitData) {
        Map<String, String> pairs = parsePairs(rawInitData);
        String hashHex = pairs.get("hash");
        if (hashHex == null) {
            throw new InvalidInitDataException(Reason.FORMAT);
        }
        byte[] expectedHash = decodeHex(hashHex);

        // Decode each value exactly once; the decoded form is what MAX signs.
        Map<String, String> decoded = new LinkedHashMap<>();
        for (Map.Entry<String, String> pair : pairs.entrySet()) {
            if (!pair.getKey().equals("hash")) {
                decoded.put(pair.getKey(), percentDecode(pair.getValue()));
            }
        }
        List<Map.Entry<String, String>> signed = new ArrayList<>(decoded.entrySet());
        signed.sort(Comparator.comparing(Map.Entry::getKey));
        StringBuilder check = new StringBuilder();
        for (Map.Entry<String, String> pair : signed) {
            if (check.length() > 0) {
                check.append('\n');
            }
            check.append(pair.getKey()).append('=').append(pair.getValue());
        }
        byte[] actualHash = hmacSha256(secretKey, check.toString().getBytes(StandardCharsets.UTF_8));
        if (!MessageDigest.isEqual(expectedHash, actualHash)) {
            throw new InvalidInitDataException(Reason.SIGNATURE);
        }

        // Only a valid signature authorizes parsing identity fields.
        long authDate = parseAuthDate(decoded.get("auth_date"));
        String userJson = decoded.get("user");
        JsonNode user = parseUser(userJson);
        checkFreshness(authDate);

        long maxUserId = requireUserId(user);
        return new MaxVerifiedUser(maxUserId, displayName(user), userJson);
    }

    private Map<String, String> parsePairs(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new InvalidInitDataException(Reason.FORMAT);
        }
        Map<String, String> pairs = new LinkedHashMap<>();
        for (String segment : raw.split("&", -1)) {
            int eq = segment.indexOf('=');
            if (eq <= 0) {
                throw new InvalidInitDataException(Reason.FORMAT);
            }
            if (pairs.putIfAbsent(segment.substring(0, eq), segment.substring(eq + 1)) != null) {
                throw new InvalidInitDataException(Reason.FORMAT);
            }
        }
        return pairs;
    }

    /** Percent-decode exactly once. Like JS decodeURIComponent, '+' is NOT a space. */
    private static String percentDecode(String value) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%') {
                if (i + 2 > value.length() - 1) {
                    throw new InvalidInitDataException(Reason.FORMAT);
                }
                int hi = hexDigit(value.charAt(i + 1));
                int lo = hexDigit(value.charAt(i + 2));
                if (hi < 0 || lo < 0) {
                    throw new InvalidInitDataException(Reason.FORMAT);
                }
                bytes.write((hi << 4) | lo);
                i += 2;
            } else {
                bytes.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new InvalidInitDataException(Reason.FORMAT);
        }
    }

    private long parseAuthDate(String decodedValue) {
        if (decodedValue == null) {
            throw new InvalidInitDataException(Reason.FORMAT);
        }
        try {
            return Long.parseLong(decodedValue);
        } catch (NumberFormatException e) {
            throw new InvalidInitDataException(Reason.FORMAT);
        }
    }

    private JsonNode parseUser(String userJson) {
        if (userJson == null) {
            throw new InvalidInitDataException(Reason.FORMAT);
        }
        try {
            JsonNode user = json.readTree(userJson);
            if (user == null || !user.isObject()) {
                throw new InvalidInitDataException(Reason.FORMAT);
            }
            return user;
        } catch (RuntimeException e) {
            if (e instanceof InvalidInitDataException invalid) {
                throw invalid;
            }
            throw new InvalidInitDataException(Reason.FORMAT);
        }
    }

    private void checkFreshness(long authDateEpochSeconds) {
        Instant now = Instant.now(clock);
        Instant auth;
        try {
            auth = Instant.ofEpochSecond(authDateEpochSeconds);
        } catch (RuntimeException e) {
            throw new InvalidInitDataException(Reason.FORMAT);
        }
        if (auth.isBefore(now.minus(MAX_AGE)) || auth.isAfter(now.plus(MAX_FUTURE_SKEW))) {
            throw new InvalidInitDataException(Reason.TIME);
        }
    }

    private long requireUserId(JsonNode user) {
        JsonNode id = user.get("id");
        if (id == null || !id.isIntegralNumber() || !id.canConvertToLong()) {
            throw new InvalidInitDataException(Reason.FORMAT);
        }
        return id.asLong();
    }

    private static String displayName(JsonNode user) {
        String first = textOrNull(user.get("first_name"));
        String last = textOrNull(user.get("last_name"));
        String joined = (first == null ? "" : first) + (first != null && last != null ? " " : "") + (last == null ? "" : last);
        return joined.isBlank() ? null : joined;
    }

    private static String textOrNull(JsonNode node) {
        return node != null && node.isString() ? node.asString() : null;
    }

    private static byte[] decodeHex(String hex) {
        if (hex.length() != HASH_BYTES * 2) {
            throw new InvalidInitDataException(Reason.FORMAT);
        }
        byte[] out = new byte[HASH_BYTES];
        for (int i = 0; i < out.length; i++) {
            int hi = hexDigit(hex.charAt(i * 2));
            int lo = hexDigit(hex.charAt(i * 2 + 1));
            if (hi < 0 || lo < 0) {
                throw new InvalidInitDataException(Reason.FORMAT);
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static int hexDigit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
