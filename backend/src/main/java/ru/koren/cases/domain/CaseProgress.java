package ru.koren.cases.domain;

import java.util.List;

/** Derived from all original items: removing withdrawn items must not turn a case into success. */
public record CaseProgress(int total, int confirmed, int withdrawn, int remaining, boolean allConfirmed) {
    public static CaseProgress from(List<ItemState> items) {
        if (items.stream().map(ItemState::itemId).distinct().count() != items.size()) {
            throw new IllegalArgumentException("Duplicate case item");
        }
        int confirmed = (int) items.stream().filter(ItemState::confirmedByAuthor).count();
        int withdrawn = (int) items.stream()
                .filter(item -> item.administrativeStatus() == ItemState.AdministrativeStatus.WITHDRAWN).count();
        return new CaseProgress(items.size(), confirmed, withdrawn, items.size() - confirmed - withdrawn,
                !items.isEmpty() && confirmed == items.size());
    }
}
