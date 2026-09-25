package ru.koren.cases.domain;

import java.util.Objects;
import java.util.UUID;

/** Current projection only. Persist command history separately before exposing write APIs. */
public record ItemState(
        UUID itemId, UUID authorId, int contentVersion, int workRound,
        WorkStatus workStatus, Observation observation,
        AdministrativeStatus administrativeStatus) {
    public enum WorkStatus { NOT_ASSIGNED, PLANNED, REPORTED_DONE }
    public enum Observation { NOT_RECEIVED, RESTORED, PERSISTS, CANNOT_CHECK }
    public enum AdministrativeStatus { ACTIVE, ARCHIVED, WITHDRAWN }
    public enum ObservationSource { AFTER_REPORT, INDEPENDENT }

    public ItemState {
        Objects.requireNonNull(itemId);
        Objects.requireNonNull(authorId);
        Objects.requireNonNull(workStatus);
        Objects.requireNonNull(observation);
        Objects.requireNonNull(administrativeStatus);
        if (contentVersion < 1 || workRound < 0) {
            throw new IllegalArgumentException("Invalid item version or work round");
        }
        if (workStatus != WorkStatus.NOT_ASSIGNED && workRound == 0) {
            throw new IllegalArgumentException("A work report requires a positive round");
        }
    }

    public boolean confirmedByAuthor() {
        return administrativeStatus != AdministrativeStatus.WITHDRAWN && observation == Observation.RESTORED;
    }

    /** Actor ID must come from an authenticated server context, never a request field. */
    public ItemState observe(UUID actorId, Observation value, ObservationSource source,
                             int expectedContentVersion, int expectedWorkRound) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(value);
        if (!authorId.equals(actorId)) throw new SecurityException("Only the author may observe this item");
        if (expectedContentVersion != contentVersion || expectedWorkRound != workRound) {
            throw new IllegalStateException("Stale item content or work round");
        }
        if (administrativeStatus == AdministrativeStatus.WITHDRAWN) {
            throw new IllegalStateException("Withdrawn item cannot receive observations");
        }
        if (value == Observation.NOT_RECEIVED) throw new IllegalArgumentException("An explicit observation is required");
        if (source == ObservationSource.AFTER_REPORT && workStatus != WorkStatus.REPORTED_DONE) {
            throw new IllegalStateException("No report of completed work for this round");
        }
        return new ItemState(itemId, authorId, contentVersion, workRound, workStatus, value, administrativeStatus);
    }

    /** Final by contract: there is no route back from ARCHIVED or WITHDRAWN, only a new item. */
    public ItemState decide(UUID actorId, AdministrativeStatus decision) {
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(decision);
        if (decision == AdministrativeStatus.ACTIVE) {
            throw new IllegalArgumentException("An administrative decision must archive or withdraw");
        }
        if (administrativeStatus != AdministrativeStatus.ACTIVE) {
            throw new IllegalStateException("The item already carries a final administrative decision");
        }
        if (decision == AdministrativeStatus.WITHDRAWN && !authorId.equals(actorId)) {
            throw new SecurityException("Only the author may withdraw this item");
        }
        return new ItemState(itemId, authorId, contentVersion, workRound, workStatus, observation, decision);
    }

    /** Called only after staff authorization, inside a transaction that also stores the report and audit. */
    public ItemState nextWorkRound(WorkStatus status) {
        Objects.requireNonNull(status);
        if (status == WorkStatus.NOT_ASSIGNED) throw new IllegalArgumentException("A report must describe work");
        if (administrativeStatus == AdministrativeStatus.WITHDRAWN) throw new IllegalStateException("Item is withdrawn");
        return new ItemState(itemId, authorId, contentVersion, Math.addExact(workRound, 1), status,
                Observation.NOT_RECEIVED, administrativeStatus);
    }
}
