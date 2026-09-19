"""Deterministic rule-based baseline for /internal/v1/compare (mode RULES).

Transparent substring rules over a normalized copy of the report (lowercase,
collapsed whitespace). Evidence offsets always refer to the original
reportText in Unicode code points [start, end). This is a wiring baseline,
not an NLP-quality claim.
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

RULES_MODEL_VERSION = "rules-baseline-v1"

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


def _relevant_sentences(norm, markers, floor, entrance):
    matched: list[tuple[int, int]] = []
    foreign: list[tuple[int, int]] = []
    for start, end in _sentence_spans(norm):
        fragment = norm[start:end]
        if not _has_marker(fragment, markers):
            continue
        if _location_conflict(fragment, floor, entrance):
            foreign.append((start, end))
        else:
            matched.append((start, end))
    return matched, foreign


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


def _sentence_with_marker(norm: str, matched: list[tuple[int, int]], markers) -> tuple[int, int]:
    context = norm[matched[0][0] : matched[-1][1]]
    hits = _positive_hits(context, markers)
    if not hits:
        return matched[0]
    absolute = matched[0][0] + hits[0]
    for span in matched:
        if span[0] <= absolute < span[1]:
            return span
    return matched[0]


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


def analyze_item(report_text: str, item: Item) -> tuple[Relation, Evidence | None]:
    markers = _markers_for(item)
    if not markers:
        return Relation.INSUFFICIENT_INFORMATION, None
    norm, origin = _normalize(report_text)
    floor, entrance = _item_numbers(item.location)
    matched, foreign = _relevant_sentences(norm, markers, floor, entrance)
    if not matched:
        if foreign:
            return Relation.UNRELATED, _build_evidence(report_text, origin, *foreign[0])
        return Relation.INSUFFICIENT_INFORMATION, None
    context_start, context_end = matched[0][0], matched[-1][1]
    relation = _classify(norm[context_start:context_end])
    if relation == Relation.INSUFFICIENT_INFORMATION:
        return relation, None
    if relation in (Relation.REPORTED_DONE, Relation.PLANNED):
        markerset = DONE_MARKERS if relation == Relation.REPORTED_DONE else PLANNED_MARKERS
        span = _sentence_with_marker(norm, matched, markerset)
    else:
        span = (context_start, context_end)
    evidence = _build_evidence(report_text, origin, *span)
    if evidence is None:
        return Relation.INSUFFICIENT_INFORMATION, None
    return relation, evidence


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
