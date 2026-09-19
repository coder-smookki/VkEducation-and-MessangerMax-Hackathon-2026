package ru.koren.cases.application;

import java.util.UUID;
import ru.koren.cases.domain.ItemState;
import ru.koren.cases.domain.ItemState.AdministrativeStatus;
import ru.koren.cases.domain.ItemState.Observation;
import ru.koren.cases.domain.ItemState.WorkStatus;

/** Read projection of one item; the four independent facts come from the current revision and projection row. */
public record CaseItemView(UUID itemId, long itemVersion, int contentVersion, String category, String location,
                           String symptom, int workRound, WorkStatus workStatus, Observation observation,
                           AdministrativeStatus administrativeStatus, String administrativeReason) {
    public CaseItemView {
        if (administrativeStatus != AdministrativeStatus.ACTIVE
                && (administrativeReason == null || administrativeReason.isBlank())) {
            throw new IllegalArgumentException("Non-active item requires a reason");
        }
    }

    static CaseItemView of(ru.koren.cases.persistence.CaseRepository.ItemRow row) {
        return new CaseItemView(row.id(), row.version(), row.contentVersion(), row.category(), row.location(),
                row.symptom(), row.workRound(), ItemState.WorkStatus.valueOf(row.workStatus()),
                ItemState.Observation.valueOf(row.observation()),
                ItemState.AdministrativeStatus.valueOf(row.administrativeStatus()), row.administrativeReason());
    }
}
