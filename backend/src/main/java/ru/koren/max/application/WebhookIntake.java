package ru.koren.max.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.koren.max.persistence.InboxRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Verifies the secret, bounds the body and stores the update once. Никакой бизнес-логики здесь нет. */
public class WebhookIntake {

    /** A webhook body is small; anything larger is not an update we asked for. */
    public static final int MAX_BODY_BYTES = 64 * 1024;

    public enum Outcome { ACCEPTED, DUPLICATE, UNAUTHORIZED, TOO_LARGE, MALFORMED }

    private final InboxRepository inbox;
    private final TransactionTemplate tx;
    private final byte[] secret;
    private final JsonMapper json = JsonMapper.builder().build();

    public WebhookIntake(InboxRepository inbox, PlatformTransactionManager transactions, String secret) {
        this.inbox = inbox;
        this.tx = new TransactionTemplate(transactions);
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public Outcome accept(String presentedSecret, byte[] body) {
        if (presentedSecret == null
            || !MessageDigest.isEqual(secret, presentedSecret.getBytes(StandardCharsets.UTF_8))) {
            return Outcome.UNAUTHORIZED;
        }
        if (body == null || body.length == 0 || body.length > MAX_BODY_BYTES) return Outcome.TOO_LARGE;
        JsonNode update;
        try {
            update = json.readTree(new String(body, StandardCharsets.UTF_8));
        } catch (RuntimeException malformed) {
            return Outcome.MALFORMED;
        }
        if (update == null || !update.isObject()) return Outcome.MALFORMED;
        String payload = update.toString();
        String hash = sha256(payload);
        Boolean stored = tx.execute(status -> inbox.accept(eventKey(update, hash), hash, payload));
        return Boolean.TRUE.equals(stored) ? Outcome.ACCEPTED : Outcome.DUPLICATE;
    }

    /**
     * Dedup key. MAX's real update envelope is not confirmed against a live bot yet, so an update
     * without a stable identifier falls back to its content hash: weaker, but never wrong about
     * identity. Confirm the field names against real traffic before relying on the first branch.
     */
    private static String eventKey(JsonNode update, String payloadHash) {
        for (String field : new String[] {"update_id", "event_id", "message_id"}) {
            JsonNode value = update.get(field);
            if (value != null && !value.isNull() && !value.asString().isBlank()) {
                return field + ":" + value.asString();
            }
        }
        return "hash:" + payloadHash;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
