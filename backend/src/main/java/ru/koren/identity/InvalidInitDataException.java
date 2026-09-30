package ru.koren.identity;

/** Rejection of unverified MAX initData. Never carries the bot token or raw init data. */
public final class InvalidInitDataException extends RuntimeException {

    public enum Reason {
        FORMAT,
        SIGNATURE,
        TIME
    }

    private final Reason reason;

    public InvalidInitDataException(Reason reason) {
        super("Invalid MAX initData (" + reason + ")");
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
