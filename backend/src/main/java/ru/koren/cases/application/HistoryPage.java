package ru.koren.cases.application;

import java.util.List;

/** One ascending page of history. The cursor is bound to its case and is never a credential. */
public record HistoryPage(List<HistoryEvent> items, String nextCursor) {
    public HistoryPage {
        items = List.copyOf(items);
    }
}
