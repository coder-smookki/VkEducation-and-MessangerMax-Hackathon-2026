package ru.koren.identity;

import java.util.UUID;


/** One house context of the current user. The role belongs to this house only. */
public record MembershipView(UUID houseId, String address, String role) {
}
