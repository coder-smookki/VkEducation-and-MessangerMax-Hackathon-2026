package ru.koren.max;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import ru.koren.max.application.MaxDeliveryException;
import ru.koren.max.application.MaxDeliveryException.Kind;

import static org.junit.jupiter.api.Assertions.*;

/** S-07 / QA-R05: what each MAX answer means for delivery. Pure mapping, no database or network. */
class MaxDeliveryExceptionTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    private static MaxDeliveryException answer(int status, String retryAfter) {
        return MaxDeliveryException.fromHttp(status, retryAfter, NOW).orElseThrow();
    }

    @Test
    void successIsNotAFailure() {
        assertTrue(MaxDeliveryException.fromHttp(200, null, NOW).isEmpty());
        assertTrue(MaxDeliveryException.fromHttp(204, null, NOW).isEmpty());
    }

    @Test
    void eachStatusFallsIntoTheRightKind() {
        assertEquals(Kind.RATE_LIMITED, answer(429, "5").kind());
        assertEquals(Kind.UNAVAILABLE, answer(500, null).kind());
        assertEquals(Kind.UNAVAILABLE, answer(503, null).kind());
        assertEquals(Kind.UNAVAILABLE, answer(408, null).kind());
        assertEquals(Kind.UNAVAILABLE, answer(401, null).kind(), "our token is wrong, not the recipient");
        assertEquals(Kind.REJECTED, answer(403, null).kind());
        assertEquals(Kind.REJECTED, answer(404, null).kind());
        assertEquals(Kind.REJECTED, answer(400, null).kind());
        assertEquals("REJECTED_403", answer(403, null).reason());
        assertEquals("UNAVAILABLE_503", answer(503, null).reason());
        assertEquals("TIMEOUT", MaxDeliveryException.timeout().reason());
    }

    @Test
    void retryAfterIsReadInBothFormsAndKeptWithinBounds() {
        assertEquals(120, answer(429, "120").retryAfterSeconds());
        assertEquals(90, answer(429, "Sun, 27 Sep 2026 10:01:30 GMT").retryAfterSeconds(), "HTTP date form");
        assertEquals(60, answer(429, null).retryAfterSeconds(), "missing header waits the default");
        assertEquals(60, answer(429, "soon").retryAfterSeconds(), "unreadable header waits the default");
        assertEquals(1, answer(429, "0").retryAfterSeconds(), "never a busy loop");
        assertEquals(1, answer(429, "Sun, 27 Sep 2026 09:00:00 GMT").retryAfterSeconds(), "a past date is now");
        assertEquals(3600, answer(429, "86400").retryAfterSeconds(), "a bad header cannot park the queue for days");
        assertEquals(3600, answer(429, "99999999999999999999").retryAfterSeconds());
    }

    @Test
    void theExceptionCarriesOnlyTheReason() {
        var failure = answer(403, null);
        assertEquals("REJECTED_403", failure.getMessage());
        assertEquals(0, failure.getStackTrace().length, "an answer from MAX is not a bug to trace");
    }
}
