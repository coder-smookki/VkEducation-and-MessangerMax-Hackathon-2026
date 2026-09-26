"""Deterministic rule-based baseline for /internal/v1/compare (mode RULES).

Transparent substring rules over a normalized copy of the report (lowercase,
collapsed whitespace). Evidence offsets always refer to the original
reportText in Unicode code points [start, end). This is a wiring baseline,
not an NLP-quality claim.

A sentence is read clause by clause. "Лампу заменили, дверь заменим" is one
sentence with two subjects, and a predicate must never travel from one to the
other: each clause belongs to the subject whose markers it carries, a clause
without any subject continues the previous one, and a clause naming two
different subjects at once makes the item abstain rather than guess.
"""

import re

from app.features.inference.schemas import (
    Category,
    CompareRequest,
    CompareResponse,
    Evidence,
    Item,
    Mode,
    Reason,
    Relation,
    Suggestion,
)

RULES_MODEL_VERSION = "rules-baseline-v2"

CATEGORY_MARKERS: dict[Category, tuple[str, ...]] = {
    Category.LIGHTING: ("ламп", "освещ", "свет"),
    Category.ENTRANCE_DOOR: ("двер", "доводчик"),
    Category.CLEANING: ("уборк", "убран", "мусор", "подмет", "вымыт", "мойк", "чистот"),
    Category.OTHER: (),
}

DONE_MARKERS = (
    "заменили",
    "заменена",
    "заменено",
    "заменены",
    "заменён",
    "поменяли",
    "поменяна",
    "поменяно",
    "поменяны",
    "починили",
    "починена",
    "починено",
    "выполнено",
    "выполнена",
    "выполнены",
    "сделано",
    "сделана",
    "восстановили",
    "восстановлено",
    "восстановлена",
    "восстановлен",
    "отрегулирован",
    "отрегулирована",
    "установили",
    "установлена",
    "установлено",
    "установлен",
    "завершено",
    "завершена",
    "завершили",
    "убрано",
    "проведена",
    "провели",
    "вывезен",
    "отремонтирован",
    "отремонтирована",
    "отремонтировали",
    "устранили",
    "устранено",
    "устранена",
    "работает",
    "исправна",
    "исправно",
    "закрывается",
)

PLANNED_MARKERS = (
    "планируется",
    "планируем",
    "запланировано",
    "запланирована",
    "запланировали",
    "будет",
    "будем",
    "будут",
    "завтра",
    "повторный выезд",
    "нужен повторный",
    "требуется",
    "заявка передана",
    "ожидается",
    "заменим",
    "починим",
    "сделаем",
    "установим",
    "выполним",
    "восстановим",
    "отрегулируем",
    "проведём",
    "наметили",
)

PERSIST_LINKERS = (
    "но ",
    "однако",
    "всё ещё",
    "все еще",
    "по-прежнему",
    "пока ",
    "так и не",
    "снова",
    "опять",
)
MALFUNCTION_PHRASES = ("света нет", "нет света", "свет нет", "пока нет")
NEGATION = re.compile(r"\bне\s+[а-яё]{3,}")
FUTURE_AUX = ("будет ", "будем ", "будут ")
CONTEXT_WINDOW = 8

ORDINALS = {
    "перв": 1,
    "втор": 2,
    "трет": 3,
    "четвёрт": 4,
    "четверт": 4,
    "пят": 5,
    "шест": 6,
    "седьм": 7,
    "восьм": 8,
    "девят": 9,
    "десят": 10,
}
FLOOR_AFTER = re.compile(r"этаж\w*\s*№?\s*(\d{1,2})")
FLOOR_BEFORE = re.compile(r"(\d{1,2}|[а-яё]+)\s+этаж\w*")
ENTRANCE_AFTER = re.compile(r"подъезд\w*\s*№?\s*(\d{1,2})")
ENTRANCE_BEFORE = re.compile(r"\b(\d{1,2})\s+подъезд\w*")
SENTENCE = re.compile(r"[^.!?]+[.!?]*")
# Punctuation cuts a clause; a coordinating conjunction starts a new one and stays in it,
# so "но"/"однако" remain visible to the persistence rule.
CLAUSE_PUNCTUATION = re.compile(r"[,;:]| — | - ")
CLAUSE_CONJUNCTION = re.compile(r" (?=(?:а|но|однако|зато|и) )")
SYMPTOM_TOKEN = re.compile(r"[а-яёa-z]{4,}")
SYMPTOM_STOPWORDS = frozenset({"работает", "работать", "очень", "плохо", "сильно", "совсем", "всё"})


def _normalize(text: str) -> tuple[str, list[int]]:
    """Lowercase copy with collapsed spaces plus a map to original offsets."""
    chars: list[str] = []
    origin: list[int] = []
    pending_space_at: int | None = None
    for position, char in enumerate(text):
        if char.isspace():
            if pending_space_at is None:
                pending_space_at = position
            continue
        if pending_space_at is not None and chars:
            chars.append(" ")
            origin.append(pending_space_at)
        pending_space_at = None
        for lowered in char.lower():
            chars.append(lowered)
            origin.append(position)
    return "".join(chars), origin


def _has_marker(text: str, markers: tuple[str, ...]) -> bool:
    return any(marker in text for marker in markers)


def _resolve_number(token: str) -> int | None:
    if token.isdigit():
        return int(token)
    for stem, value in ORDINALS.items():
        if token.startswith(stem):
            return value
    return None


def _mentioned_numbers(fragment: str, after: re.Pattern, before: re.Pattern) -> set[int]:
    found = {int(match.group(1)) for match in after.finditer(fragment)}
    for match in before.finditer(fragment):
        value = _resolve_number(match.group(1))
        if value is not None:
            found.add(value)
    return found


def _first_number(text: str, after: re.Pattern, before: re.Pattern) -> int | None:
    match = after.search(text)
    if match:
        return int(match.group(1))
    match = before.search(text)
    return _resolve_number(match.group(1)) if match else None


def _item_numbers(location: str) -> tuple[int | None, int | None]:
    text = location.lower()
    return (
        _first_number(text, FLOOR_AFTER, FLOOR_BEFORE),
        _first_number(text, ENTRANCE_AFTER, ENTRANCE_BEFORE),
    )


def _markers_for(item: Item) -> tuple[str, ...]:
    markers = CATEGORY_MARKERS[item.category]
    if markers:
        return markers
    return tuple(
        token for token in SYMPTOM_TOKEN.findall(item.symptom.lower()) if token not in SYMPTOM_STOPWORDS
    )


def _positive_hits(context: str, markers: tuple[str, ...]) -> list[int]:
    """Marker offsets that are neither negated ("не ...") nor future ("будет ...")."""
    hits = []
    for marker in markers:
        start = context.find(marker)
        while start != -1:
            before = context[max(0, start - CONTEXT_WINDOW) : start]
            if not before.endswith("не ") and not any(before.endswith(aux) for aux in FUTURE_AUX):
                hits.append(start)
            start = context.find(marker, start + 1)
    return sorted(hits)


def _sentence_spans(norm: str):
    for match in SENTENCE.finditer(norm):
        start, end = match.start(), match.end()
        while start < end and norm[start] == " ":
            start += 1
        if start < end:
            yield start, end


def _location_conflict(fragment: str, floor: int | None, entrance: int | None) -> bool:
    floors = _mentioned_numbers(fragment, FLOOR_AFTER, FLOOR_BEFORE)
    entrances = _mentioned_numbers(fragment, ENTRANCE_AFTER, ENTRANCE_BEFORE)
    return bool(
        (floor is not None and floors and floor not in floors)
        or (entrance is not None and entrances and entrance not in entrances)
    )


def _mentions_location(fragment: str) -> bool:
    return bool(
        _mentioned_numbers(fragment, FLOOR_AFTER, FLOOR_BEFORE)
        or _mentioned_numbers(fragment, ENTRANCE_AFTER, ENTRANCE_BEFORE)
    )


def _trim(norm: str, start: int, end: int) -> tuple[int, int]:
    while start < end and norm[start] == " ":
        start += 1
    while end > start and norm[end - 1] == " ":
        end -= 1
    return start, end


def _clause_spans(norm: str, start: int, end: int):
    """Clauses of one sentence; the sentence's final punctuation stays in its last clause."""
    fragment = norm[start:end]
    cuts = [(start + m.start(), start + m.end()) for m in CLAUSE_PUNCTUATION.finditer(fragment)]
    cuts += [(start + m.start(), start + m.end()) for m in CLAUSE_CONJUNCTION.finditer(fragment)]
    cursor = start
    for cut_start, cut_end in sorted(cuts):
        if cut_start > cursor:
            span = _trim(norm, cursor, cut_start)
            if span[0] < span[1]:
                yield span
        cursor = max(cursor, cut_end)
    span = _trim(norm, cursor, end)
    if span[0] < span[1]:
        yield span


def _foreign_markers(category: Category) -> tuple[str, ...]:
    """Markers of every other fixed category: a clause carrying them speaks about something else."""
    return tuple(
        marker for other, markers in CATEGORY_MARKERS.items() if other != category for marker in markers
    )


def _address_conflicts(norm: str, spans: list[tuple[int, int]], location) -> list[bool]:
    """Per clause: whether it sits at another address. A clause naming no address keeps the last one."""
    conflicts = []
    conflict = False
    for start, end in spans:
        if _mentions_location(norm[start:end]):
            conflict = _location_conflict(norm[start:end], *location)
        conflicts.append(conflict)
    return conflicts


def _sentence_item_clauses(norm, span, markers, foreign, location):
    """One sentence: this item's clauses as (position, start, end, elsewhere), a mixed flag and a count.

    A clause with no subject of its own continues the previous one.
    """
    spans = list(_clause_spans(norm, *span))
    conflicts = _address_conflicts(norm, spans, location)
    found = []
    mixed = False
    current_is_item = False
    for position, (start, end) in enumerate(spans):
        own = _has_marker(norm[start:end], markers)
        other = _has_marker(norm[start:end], foreign)
        # Two subjects, one predicate: which one it belongs to is a guess, so do not guess.
        mixed = mixed or (own and other)
        current_is_item = (own and not other) or (current_is_item and not own and not other)
        if current_is_item:
            found.append((position, start, end, conflicts[position]))
    return found, mixed, len(spans)


def _item_clauses(norm, markers, foreign, location):
    """Clauses about this item, clauses about it at another address, and whether any was mixed.

    Each entry is (index, start, end); the index is global so adjacent clauses can form one quote.
    """
    matched: list[tuple[int, int, int]] = []
    elsewhere: list[tuple[int, int, int]] = []
    ambiguous = False
    offset = 0
    for span in _sentence_spans(norm):
        found, mixed, count = _sentence_item_clauses(norm, span, markers, foreign, location)
        ambiguous = ambiguous or mixed
        matched += [(offset + position, start, end) for position, start, end, away in found if not away]
        elsewhere += [(offset + position, start, end) for position, start, end, away in found if away]
        offset += count
    return matched, elsewhere, ambiguous


def _runs(clauses: list[tuple[int, int, int]]) -> list[tuple[int, int]]:
    """Adjacent clauses merged into spans; a gap means another subject spoke in between."""
    runs: list[tuple[int, int]] = []
    previous = None
    for index, start, end in clauses:
        if previous is not None and index == previous + 1:
            runs[-1] = (runs[-1][0], end)
        else:
            runs.append((start, end))
        previous = index
    return runs


def _classify(context: str) -> Relation:
    done = bool(_positive_hits(context, DONE_MARKERS))
    negation = bool(NEGATION.search(context)) or _has_marker(context, MALFUNCTION_PHRASES)
    if done and negation:
        if _has_marker(context, PERSIST_LINKERS):
            return Relation.SYMPTOM_PERSISTS
        return Relation.CONFLICT
    if not done and _positive_hits(context, PLANNED_MARKERS):
        return Relation.PLANNED
    if done:
        return Relation.REPORTED_DONE
    return Relation.INSUFFICIENT_INFORMATION


def _signal_for(relation: Relation):
    """What makes a run of clauses the right quote for this relation."""
    if relation == Relation.REPORTED_DONE:
        return lambda text: bool(_positive_hits(text, DONE_MARKERS))
    if relation == Relation.PLANNED:
        return lambda text: bool(_positive_hits(text, PLANNED_MARKERS))
    return lambda text: bool(NEGATION.search(text)) or _has_marker(text, MALFUNCTION_PHRASES)


def _quote_span(norm: str, runs: list[tuple[int, int]], relation: Relation) -> tuple[int, int]:
    """The run that carries the decisive signal; never a span that crosses another subject."""
    signal = _signal_for(relation)
    for start, end in runs:
        if signal(norm[start:end]):
            return start, end
    return runs[0]


def _build_evidence(report_text: str, origin: list[int], start: int, end: int) -> Evidence | None:
    if start >= end:
        return None
    original_start = origin[start]
    original_end = origin[end - 1] + 1
    quote = report_text[original_start:original_end]
    if not quote:
        return None
    try:
        return Evidence(start=original_start, end=original_end, quote=quote)
    except ValueError:
        return None


ABSTAIN: tuple[Relation, None] = (Relation.INSUFFICIENT_INFORMATION, None)


def analyze_item(report_text: str, item: Item) -> tuple[Relation, Evidence | None]:
    markers = _markers_for(item)
    if not markers:
        return ABSTAIN
    norm, origin = _normalize(report_text)
    matched, elsewhere, ambiguous = _item_clauses(
        norm, markers, _foreign_markers(item.category), _item_numbers(item.location)
    )
    if ambiguous or not (matched or elsewhere):
        return ABSTAIN
    if not matched:
        start, end = _runs(elsewhere)[0]
        return Relation.UNRELATED, _build_evidence(report_text, origin, start, end)
    # Only this item's clauses are read. The separator carries no space: the persistence
    # linker "но " is a substring rule, and "восстановлено " would otherwise match it.
    relation = _classify("|".join(norm[start:end] for _, start, end in matched))
    evidence = (
        None
        if relation == Relation.INSUFFICIENT_INFORMATION
        else _build_evidence(report_text, origin, *_quote_span(norm, _runs(matched), relation))
    )
    return ABSTAIN if evidence is None else (relation, evidence)


def analyze_rules(request: CompareRequest) -> CompareResponse:
    """Advisory comparison only; never changes product state."""
    suggestions = []
    for item in request.items:
        relation, evidence = analyze_item(request.reportText, item)
        suggestions.append(
            Suggestion(
                itemId=item.itemId,
                contentVersion=item.contentVersion,
                workRound=item.workRound,
                relation=relation,
                evidence=evidence,
                reason=Reason.ANALYZED,
            )
        )
    return CompareResponse(
        caseId=request.caseId,
        sourceRevision=request.sourceRevision,
        reportId=request.reportId,
        modelVersion=RULES_MODEL_VERSION,
        mode=Mode.RULES,
        suggestions=suggestions,
    )
