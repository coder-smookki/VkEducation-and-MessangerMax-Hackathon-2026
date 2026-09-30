package ru.koren.cases.application;

import java.time.Instant;
import java.util.UUID;
import ru.koren.cases.persistence.CaseRepository.ItemRow;

/**
 * A plan, never a report: who will do what, and when the case will be updated next.
 * It never implies that work happened, and the server never shifts an overdue updateDueAt forward.
 */
public record NextActionView(String text, UUID ownerId, String ownerDisplayName,
                             Instant updateDueAt, Instant updatedAt) {

    /** null when the item carries no plan; a CHECK keeps the columns either all filled or all empty. */
    static NextActionView of(ItemRow row) {
        if (row.nextActionText() == null) return null;
        // ownerDisplayName stays null until app_user gets a display name column (same gap as /me).
        return new NextActionView(row.nextActionText(), row.nextActionOwnerId(), null,
            row.nextActionUpdateDueAt() == null ? null : row.nextActionUpdateDueAt().toInstant(),
            row.nextActionUpdatedAt().toInstant());
    }
}
