package ru.koren.identity;

/**
 * User identity proven by a valid MAX initData signature.
 * Profile fields may be null; rawUserJson is the exact user JSON as signed by MAX.
 * The raw JSON is not stored in the session or exposed through the API.
 */
public record MaxVerifiedUser(long maxUserId, MaxProfile profile, String rawUserJson) {
}
