package ru.koren.max.application;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The real sender against a local stand-in for MAX: what goes over the wire (path, header, body)
 * and how each answer is told apart for the outbox. No Spring and no database are needed.
 */
class HttpMaxSenderTest {

    record Seen(String method, String path, String query, String authorization, String body) {}

    private static final String TOKEN = "k1-test-bot-token:not-a-real-secret";
    private static final String BOT = "t109_hakaton_max_bot";
    private static final long RESIDENT_MAX_ID = 1001L;
    private static final UUID RESIDENT = UUID.randomUUID();
    private static final UUID NEVER_OPENED_BOT = UUID.randomUUID();
    private static final UUID CASE = UUID.randomUUID();

    private final JsonMapper json = JsonMapper.builder().build();
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private volatile int status;
    private volatile String retryAfter;
    private volatile long delayMillis;
    private HttpServer max;

    private final HttpMaxSender.Recipients recipients = new HttpMaxSender.Recipients() {
        @Override
        public OptionalLong maxUserId(UUID userId) {
            return userId.equals(RESIDENT) ? OptionalLong.of(RESIDENT_MAX_ID) : OptionalLong.empty();
        }

        @Override
        public Optional<Long> displayNumber(UUID caseId) {
            return caseId.equals(CASE) ? Optional.of(124L) : Optional.empty();
        }
    };

    @BeforeEach
    void startMax() throws IOException {
        status = 200;
        retryAfter = null;
        delayMillis = 0;
        seen.clear();
        max = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        max.createContext("/", this::answer);
        max.start();
    }

    @AfterEach
    void stopMax() {
        max.stop(0);
    }

    private void answer(HttpExchange exchange) {
        try {
            seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getRawQuery(), exchange.getRequestHeaders().getFirst("Authorization"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            if (delayMillis > 0) Thread.sleep(delayMillis);
            if (retryAfter != null) exchange.getResponseHeaders().add("Retry-After", retryAfter);
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        } catch (IOException | InterruptedException clientGaveUp) {
            // The timeout test hangs up first; nothing to answer then.
        } finally {
            exchange.close();
        }
    }

    private HttpMaxSender sender(Duration timeout) {
        String url = "http://127.0.0.1:" + max.getAddress().getPort() + "/";
        return new HttpMaxSender(HttpClient.newHttpClient(), url, TOKEN, BOT, "", recipients, timeout);
    }

    private HttpMaxSender sender() {
        return sender(Duration.ofSeconds(5));
    }

    private MaxDeliveryException refusedWith(int answerStatus) {
        status = answerStatus;
        return assertThrows(MaxDeliveryException.class,
            () -> sender().send(RESIDENT, "CASE_ACCEPTED", "{\"caseId\":\"" + CASE + "\"}"));
    }

    @Test
    void caseNewsGoesToTheResidentWithTheNumberAndAButtonIntoTheCase() {
        sender().send(RESIDENT, "CASE_ACCEPTED", "{\"caseId\":\"" + CASE + "\"}");

        assertEquals(1, seen.size());
        Seen request = seen.getFirst();
        assertEquals("POST", request.method());
        assertEquals("/messages", request.path());
        assertEquals("user_id=" + RESIDENT_MAX_ID, request.query());
        assertEquals(TOKEN, request.authorization(), "MAX takes the bare token, without Bearer");
        JsonNode body = json.readTree(request.body());
        String text = body.get("text").asString();
        assertTrue(text.contains("№124"), text);
        assertTrue(text.contains("принято в работу"), text);
        JsonNode keyboard = body.get("attachments").get(0);
        assertEquals("inline_keyboard", keyboard.get("type").asString());
        JsonNode button = keyboard.get("payload").get("buttons").get(0).get(0);
        assertEquals("link", button.get("type").asString());
        assertEquals("https://max.ru/" + BOT + "?startapp=" + CASE, button.get("url").asString());
    }

    @Test
    void aLinkedReportDoesNotClaimCompletionWithoutAWorkStatus() {
        // Both PLANNED and REPORTED_DONE links produce this same payload. Only the case view
        // knows which work status was saved; a notification must not turn a plan into completion.
        sender().send(RESIDENT, "REPORT_LINK_CONFIRMED",
            "{\"caseId\":\"" + CASE + "\",\"itemId\":\"" + UUID.randomUUID() + "\"}");

        assertEquals(1, seen.size());
        JsonNode body = json.readTree(seen.getFirst().body());
        String text = body.get("text").asString();
        assertTrue(text.contains("№124"), text);
        assertTrue(text.contains("сведения о работах"), text);
        assertFalse(text.contains("выполн"), "A planned report is not completed work: " + text);
        assertFalse(text.contains("помогло"), "A planned report does not yet ask about the result: " + text);
        JsonNode button = body.get("attachments").get(0).get("payload").get("buttons").get(0).get(0);
        assertEquals("https://max.ru/" + BOT + "?startapp=" + CASE, button.get("url").asString());
    }

    @Test
    void aBotReplyIsSentAsWrittenWithoutAButton() {
        sender().send(RESIDENT, BotDialog.REPLY_EVENT, "{\"text\":\"Записал. Опишите пункт.\"}");

        JsonNode body = json.readTree(seen.getFirst().body());
        assertEquals("Записал. Опишите пункт.", body.get("text").asString());
        assertNull(body.get("attachments"));
    }

    @Test
    void aTooLongReplyIsClippedToWhatMaxAccepts() {
        String longText = "а".repeat(HttpMaxSender.MAX_TEXT + 500);
        sender().send(RESIDENT, BotDialog.REPLY_EVENT, json.writeValueAsString(java.util.Map.of("text", longText)));

        assertEquals(HttpMaxSender.MAX_TEXT, json.readTree(seen.getFirst().body()).get("text").asString().length());
    }

    @Test
    void anUnknownEventStillNamesTheCase() {
        sender().send(RESIDENT, "SOMETHING_NEW", "{\"caseId\":\"" + CASE + "\"}");

        assertTrue(json.readTree(seen.getFirst().body()).get("text").asString().contains("№124"));
    }

    @Test
    void someoneWhoNeverOpenedTheBotIsRefusedWithoutCallingMax() {
        var refusal = assertThrows(MaxDeliveryException.class,
            () -> sender().send(NEVER_OPENED_BOT, "CASE_CONFIRMED", "{\"caseId\":\"" + CASE + "\"}"));

        assertEquals(MaxDeliveryException.Kind.REJECTED, refusal.kind());
        assertEquals("NO_MAX_USER", refusal.reason());
        assertTrue(seen.isEmpty());
    }

    @Test
    void maxAnswersAreToldApartForTheOutbox() {
        retryAfter = "7";
        var limited = refusedWith(429);
        assertEquals(MaxDeliveryException.Kind.RATE_LIMITED, limited.kind());
        assertEquals(7, limited.retryAfterSeconds());

        retryAfter = null;
        assertEquals(MaxDeliveryException.Kind.REJECTED, refusedWith(400).kind());
        assertEquals(MaxDeliveryException.Kind.UNAVAILABLE, refusedWith(401).kind(), "our token, worth another try");
        assertEquals(MaxDeliveryException.Kind.UNAVAILABLE, refusedWith(500).kind());
    }

    @Test
    void aSlowMaxIsATimeoutNotAHang() {
        delayMillis = 2_000;
        var refusal = assertThrows(MaxDeliveryException.class, () -> sender(Duration.ofMillis(300))
            .send(RESIDENT, BotDialog.REPLY_EVENT, "{\"text\":\"ok\"}"));

        assertEquals(MaxDeliveryException.Kind.UNAVAILABLE, refusal.kind());
        assertEquals("TIMEOUT", refusal.reason());
    }

    @Test
    void twoMessagesToOnePersonStayWithinTwoASecond() {
        var sender = sender();
        long started = System.nanoTime();
        sender.send(RESIDENT, BotDialog.REPLY_EVENT, "{\"text\":\"первое\"}");
        sender.send(RESIDENT, BotDialog.REPLY_EVENT, "{\"text\":\"второе\"}");

        assertEquals(2, seen.size());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() >= 450);
    }
}
