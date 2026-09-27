package ru.koren.max.application;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends one outbox row as one MAX message: POST /messages?user_id=..., token in the Authorization
 * header as MAX requires (no "Bearer", never in the query). Every answer is turned into a
 * MaxDeliveryException the outbox understands; nothing from the payload or the response body is
 * logged or stored. Case news carries only the case number and a button into the Mini App: the
 * card itself is read there, where access is checked on every open.
 */
public class HttpMaxSender implements MaxSender {

    /** MAX cuts text at 4000 characters; clipping here keeps a long reply from being refused. */
    static final int MAX_TEXT = 4000;
    /** MAX allows two messages a second per dialog; half a second apart stays inside it. */
    static final long MIN_GAP_NANOS = 500_000_000L;

    /** Where the recipient's MAX id and the case number come from; the database in production. */
    public interface Recipients {
        OptionalLong maxUserId(UUID userId);

        Optional<Long> displayNumber(UUID caseId);
    }

    private final HttpClient http;
    private final String apiUrl;
    private final String token;
    private final String botName;
    private final String numberPrefix;
    private final Recipients recipients;
    private final Duration timeout;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<Long, Long> lastSentAt = new ConcurrentHashMap<>();

    public HttpMaxSender(HttpClient http, String apiUrl, String token, String botName, String numberPrefix,
                         Recipients recipients, Duration timeout) {
        this.http = http;
        this.apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        this.token = token;
        this.botName = botName == null ? "" : botName.trim();
        this.numberPrefix = numberPrefix == null ? "" : numberPrefix;
        this.recipients = recipients;
        this.timeout = timeout;
    }

    @Override
    public void send(UUID recipientId, String eventType, String payload) {
        long userId = recipients.maxUserId(recipientId).orElseThrow(MaxDeliveryException::noRecipient);
        String body = json.writeValueAsString(message(eventType, json.readTree(payload)));
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl + "/messages?user_id=" + userId))
            .timeout(timeout)
            .header("Authorization", token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        pace(userId);
        HttpResponse<Void> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (HttpTimeoutException slow) {
            throw MaxDeliveryException.timeout();
        } catch (IOException unreachable) {
            // Retried by the outbox like any other failure; only the class name is kept there.
            throw new UncheckedIOException(unreachable.getClass().getSimpleName(), unreachable);
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
            throw MaxDeliveryException.timeout();
        }
        var refusal = MaxDeliveryException.fromHttp(response.statusCode(),
            response.headers().firstValue("Retry-After").orElse(null), Instant.now());
        if (refusal.isPresent()) throw refusal.get();
    }

    Map<String, Object> message(String eventType, JsonNode payload) {
        var message = new LinkedHashMap<String, Object>();
        if (BotDialog.REPLY_EVENT.equals(eventType)) {
            // The bot's own answer, already worded for this person.
            message.put("text", clip(string(payload, "text")));
            return message;
        }
        UUID caseId = caseId(payload);
        String number = caseId == null ? "" : recipients.displayNumber(caseId)
            .map(value -> " №" + numberPrefix + value)
            .orElse("");
        message.put("text", clip(textFor(eventType, number) + " Подробности - в приложении."));
        if (caseId != null && !botName.isEmpty()) {
            var button = Map.of("type", "link", "text", "Открыть обращение",
                "url", "https://max.ru/" + botName + "?startapp=" + caseId);
            message.put("attachments", List.of(Map.of("type", "inline_keyboard",
                "payload", Map.of("buttons", List.of(List.of(button))))));
        }
        return message;
    }

    /** One line per event CaseService queues; an unknown type still tells the person to look. */
    static String textFor(String eventType, String number) {
        return switch (eventType) {
            case "CASE_CONFIRMED" -> "Новое обращение" + number + " подтверждено жителем и ждёт в очереди.";
            case "CASE_ACCEPTED" -> "Обращение" + number + " принято в работу.";
            case "ITEM_NEXT_ACTION_SET" -> "По обращению" + number + " назначен следующий шаг.";
            case "REPORT_LINK_CONFIRMED" -> "По обращению" + number + " появились новые сведения о работах.";
            case "OBSERVATION_RECORDED" -> "Житель ответил, помогли ли работы по обращению" + number + ".";
            case "ITEM_WITHDRAWN" -> "Житель отозвал пункт в обращении" + number + ".";
            case "ITEM_ARCHIVED" -> "Пункт в обращении" + number + " перенесён в архив.";
            default -> "Есть новости по обращению" + number + ".";
        };
    }

    /**
     * The outbox worker sends one row at a time, so a short wait here is enough to keep a reply and a
     * notification to the same person from tripping MAX's per-dialog limit.
     */
    private void pace(long userId) {
        Long previous = lastSentAt.get(userId);
        if (previous != null) {
            long wait = MIN_GAP_NANOS - (System.nanoTime() - previous);
            if (wait > 0) LockSupport.parkNanos(wait);
        }
        // Only the last half second matters; an old map is simply dropped.
        if (lastSentAt.size() > 10_000) lastSentAt.clear();
        lastSentAt.put(userId, System.nanoTime());
    }

    private static UUID caseId(JsonNode payload) {
        String raw = string(payload, "caseId");
        try {
            return raw.isEmpty() ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static String string(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isString() ? value.asString() : "";
    }

    private static String clip(String text) {
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT);
    }
}
