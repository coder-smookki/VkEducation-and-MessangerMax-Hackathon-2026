package ru.koren.identity;

/** Invitation failure with a stable code. The reason stays out of the response to slow guessing down. */
public final class InvitationException extends RuntimeException {

    private final String code;
    private final int httpStatus;

    public InvitationException(String code, int httpStatus) {
        super("Invitation rejected");
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() { return code; }
    public int httpStatus() { return httpStatus; }
}
