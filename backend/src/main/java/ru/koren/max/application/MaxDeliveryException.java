package ru.koren.max.application;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Why MAX did not take a message, in the three kinds the outbox treats differently: wait as told,
 * try again later, or stop. The message is only the reason code, never the payload or the response
 * body, because both may carry user text. No stack trace is kept: this is an answer, not a bug.
 */
public final class MaxDeliveryException extends RuntimeException {

    public enum Kind {
        /** 429: the bot is over its limit. Every queued message waits, not only this one. */
        RATE_LIMITED,
        /** 5xx, 408, timeout, and 401 - our own token, not the recipient's fault. Worth another try. */
        UNAVAILABLE,
        /** Any other 4xx: blocked bot, missing chat, a request MAX will never accept. Retrying is noise. */
        REJECTED
    }

    static final int DEFAULT_RETRY_AFTER_SECONDS = 60;
    static final int MAX_RETRY_AFTER_SECONDS = 3600;

    private final Kind kind;
    private final String reason;
    private final int retryAfterSeconds;

    private MaxDeliveryException(Kind kind, String reason, int retryAfterSeconds) {
        super(reason, null, false, false);
        this.kind = kind;
        this.reason = reason;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Kind kind() {
        return kind;
    }

    /** Short and fixed: it is stored in outbox_event.last_error and logged. */
    public String reason() {
        return reason;
    }

    /** Only meaningful for RATE_LIMITED; always within 1 s .. 1 h. */
    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public static MaxDeliveryException timeout() {
        return new MaxDeliveryException(Kind.UNAVAILABLE, "TIMEOUT", 0);
    }

    /**
     * The recipient has no MAX account on record, e.g. a dispatcher who never opened the bot.
     * Nothing will change on the next try, so the row stops here with a reason anyone can read.
     */
    public static MaxDeliveryException noRecipient() {
        return new MaxDeliveryException(Kind.REJECTED, "NO_MAX_USER", 0);
    }

    /** What an HTTP answer of the MAX Bot API means for delivery; empty means it was accepted. */
    public static Optional<MaxDeliveryException> fromHttp(int status, String retryAfterHeader, Instant now) {
        if (status >= 200 && status < 300) return Optional.empty();
        if (status == 429) {
            return Optional.of(new MaxDeliveryException(Kind.RATE_LIMITED, "RATE_LIMITED",
                retryAfter(retryAfterHeader, now)));
        }
        boolean permanent = status >= 400 && status < 500 && status != 401 && status != 408;
        return Optional.of(permanent
            ? new MaxDeliveryException(Kind.REJECTED, "REJECTED_" + status, 0)
            : new MaxDeliveryException(Kind.UNAVAILABLE, "UNAVAILABLE_" + status, 0));
    }

    /**
     * Retry-After as delta-seconds or as an HTTP date. A missing or unreadable value waits the default;
     * a huge one is capped, so a bad header cannot park the whole queue for days.
     */
    static int retryAfter(String header, Instant now) {
        if (header == null || header.isBlank()) return DEFAULT_RETRY_AFTER_SECONDS;
        String value = header.trim();
        long seconds;
        if (value.chars().allMatch(Character::isDigit)) {
            try {
                seconds = Long.parseLong(value);
            } catch (NumberFormatException tooLong) {
                seconds = MAX_RETRY_AFTER_SECONDS;
            }
        } else {
            try {
                Instant at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                seconds = Duration.between(now, at).toSeconds();
            } catch (DateTimeParseException unreadable) {
                return DEFAULT_RETRY_AFTER_SECONDS;
            }
        }
        return (int) Math.min(MAX_RETRY_AFTER_SECONDS, Math.max(1, seconds));
    }
}
