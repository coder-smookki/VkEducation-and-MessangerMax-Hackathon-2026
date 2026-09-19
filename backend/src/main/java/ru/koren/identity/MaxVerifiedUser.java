package ru.koren.identity;

/**
 * User identity proven by a valid MAX initData signature.
 * displayName may be null; rawUserJson is the exact user JSON as signed by MAX.
 */
public record MaxVerifiedUser(long maxUserId, String displayName, String rawUserJson) {
}
