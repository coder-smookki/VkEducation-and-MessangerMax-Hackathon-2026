package ru.koren.cases.domain;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ru.koren.cases.domain.ItemState.*;

class ItemStateTest {
    private final UUID author = UUID.randomUUID();
    private ItemState item(WorkStatus work, Observation observation, AdministrativeStatus admin) {
        return new ItemState(UUID.randomUUID(), author, 1, work == WorkStatus.NOT_ASSIGNED ? 0 : 1,
                work, observation, admin);
    }

    @Test void reportIsNotResidentConfirmation() {
        var state = item(WorkStatus.REPORTED_DONE, Observation.NOT_RECEIVED, AdministrativeStatus.ACTIVE);
        assertFalse(CaseProgress.from(List.of(state)).allConfirmed());
    }
    @Test void oneRestoredItemDoesNotHideDoor() {
        var lamp = item(WorkStatus.REPORTED_DONE, Observation.RESTORED, AdministrativeStatus.ACTIVE);
        var door = item(WorkStatus.NOT_ASSIGNED, Observation.NOT_RECEIVED, AdministrativeStatus.ACTIVE);
        assertEquals(new CaseProgress(2, 1, 0, 1, false), CaseProgress.from(List.of(lamp, door)));
    }
    @Test void emptyCaseCannotBeComplete() { assertFalse(CaseProgress.from(List.of()).allConfirmed()); }
    @Test void onlyAuthorCanObserve() {
        var state = item(WorkStatus.REPORTED_DONE, Observation.NOT_RECEIVED, AdministrativeStatus.ACTIVE);
        assertThrows(SecurityException.class, () -> state.observe(UUID.randomUUID(), Observation.RESTORED,
                ObservationSource.AFTER_REPORT, 1, 1));
    }
    @Test void planDoesNotEnableAfterReportCallback() {
        var state = item(WorkStatus.PLANNED, Observation.NOT_RECEIVED, AdministrativeStatus.ACTIVE);
        assertThrows(IllegalStateException.class, () -> state.observe(author, Observation.RESTORED,
                ObservationSource.AFTER_REPORT, 1, 1));
    }
    @Test void staleRoundAndContentAreRejected() {
        var state = item(WorkStatus.REPORTED_DONE, Observation.NOT_RECEIVED, AdministrativeStatus.ACTIVE);
        assertThrows(IllegalStateException.class, () -> state.observe(author, Observation.RESTORED,
                ObservationSource.AFTER_REPORT, 1, 0));
        assertThrows(IllegalStateException.class, () -> state.observe(author, Observation.RESTORED,
                ObservationSource.AFTER_REPORT, 2, 1));
    }
    @Test void newReportInvalidatesOldObservation() {
        var state = item(WorkStatus.REPORTED_DONE, Observation.RESTORED, AdministrativeStatus.ACTIVE);
        var next = state.nextWorkRound(WorkStatus.REPORTED_DONE);
        assertEquals(2, next.workRound());
        assertEquals(Observation.NOT_RECEIVED, next.observation());
        assertFalse(next.confirmedByAuthor());
    }
    @Test void independentRestorationDoesNotInventWork() {
        var state = item(WorkStatus.NOT_ASSIGNED, Observation.NOT_RECEIVED, AdministrativeStatus.ACTIVE);
        var result = state.observe(author, Observation.RESTORED, ObservationSource.INDEPENDENT, 1, 0);
        assertTrue(result.confirmedByAuthor());
        assertEquals(WorkStatus.NOT_ASSIGNED, result.workStatus());
    }
    @Test void lateNegativeObservationRemovesConfirmation() {
        var state = item(WorkStatus.REPORTED_DONE, Observation.RESTORED, AdministrativeStatus.ACTIVE);
        assertFalse(state.observe(author, Observation.PERSISTS, ObservationSource.AFTER_REPORT, 1, 1)
                .confirmedByAuthor());
    }
    @Test void archiveAndWithdrawalAreNotSuccess() {
        for (var work : WorkStatus.values()) for (var observation : Observation.values()) {
            var withdrawn = item(work, observation, AdministrativeStatus.WITHDRAWN);
            assertFalse(withdrawn.confirmedByAuthor());
            assertThrows(IllegalStateException.class, () -> withdrawn.nextWorkRound(WorkStatus.REPORTED_DONE));
        }
        var archived = item(WorkStatus.REPORTED_DONE, Observation.CANNOT_CHECK, AdministrativeStatus.ARCHIVED);
        assertFalse(archived.confirmedByAuthor());
    }
    @Test void withdrawalCannotShrinkDenominator() {
        var lamp = item(WorkStatus.REPORTED_DONE, Observation.RESTORED, AdministrativeStatus.ACTIVE);
        var door = item(WorkStatus.NOT_ASSIGNED, Observation.NOT_RECEIVED, AdministrativeStatus.WITHDRAWN);
        assertEquals(new CaseProgress(2, 1, 1, 0, false), CaseProgress.from(List.of(lamp, door)));
    }
}
