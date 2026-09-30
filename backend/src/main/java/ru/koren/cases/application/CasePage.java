package ru.koren.cases.application;

import java.util.List;

/** One page of the visible case list; nextCursor is opaque and bound to the filter it was issued for. */
public record CasePage(List<CaseView> items, String nextCursor) {
    public CasePage {
        items = List.copyOf(items);
    }
}
