package ru.koren.cases.application;

/** Application failure with a stable code for the future HTTP problem mapping (S1, controllers come with K1). */
public class CaseException extends RuntimeException {
    private final String code;
    private final int httpStatus;

    public CaseException(String code, int httpStatus, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() { return code; }
    public int httpStatus() { return httpStatus; }

    // Never reflect caller input in messages; never leak the existence of a foreign resource.
    public static CaseException notFound() {
        return new CaseException("RESOURCE_NOT_FOUND", 404, "Resource is not available");
    }
    public static CaseException forbidden() {
        return new CaseException("ACTION_FORBIDDEN", 403, "Action is not allowed for this actor");
    }
    public static CaseException versionConflict() {
        return new CaseException("CASE_VERSION_CONFLICT", 409, "Expected version does not match the current state");
    }
    public static CaseException itemVersionConflict() {
        return new CaseException("ITEM_VERSION_CONFLICT", 409, "Expected item version does not match the current state");
    }
    public static CaseException alreadyConfirmed() {
        return new CaseException("CASE_ALREADY_CONFIRMED", 409, "Confirmed case content is frozen");
    }
    public static CaseException idempotencyKeyReused() {
        return new CaseException("IDEMPOTENCY_KEY_REUSED", 409, "Idempotency key was used with a different request");
    }
    public static CaseException invalidFields(String message) {
        return new CaseException("INVALID_FIELDS", 422, message);
    }
    public static CaseException invalidTransition(String message) {
        return new CaseException("INVALID_TRANSITION", 422, message);
    }
}
