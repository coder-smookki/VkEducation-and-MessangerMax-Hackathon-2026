"""Conservative, bounded report grammar; unsupported assertions deliberately abstain.

All target items are considered together before a clause is assigned. Nothing here
confirms a repair or writes product state. Spans always index the original string.
"""

import re
from dataclasses import dataclass, replace

from app.features.inference.schemas import (
    Category,
    CompareRequest,
    CompareResponse,
    Evidence,
    Mode,
    Reason,
    Relation,
    Suggestion,
)

RULES_MODEL_VERSION = "rules-baseline-v3"
ABSTAIN = (Relation.INSUFFICIENT_INFORMATION, None)
WORDS = re.compile(r"[а-яёa-z]+|\d+")
SENTENCES = re.compile(r"[^.!?\n]+[.!?]*")
CUTS = re.compile(
    r"[,;:]|\s+(?=(?:а|но|однако|зато)\s)|\s+(?=и\s+(?:двер\w*|ламп\w*|свет\w*|освещ\w*|уборк\w*|мусор\w*)\b)"
)
# These are scopes we cannot safely resolve with a bounded grammar, not negative examples
# to special-case. Reject the report instead of stripping its operators before classification.
UNSUPPORTED_SCOPE = re.compile(
    r"[:?«»\"“”„`'‘’‚‛ʼ]|\b(?:если|когда|бы|якобы|возможно|вероятно|кажется|предположительно|"
    r"наверное|сомнительно|сообщите|уточните|проверьте|напишите|просят|просим|"
    r"попросили|считать|считайте|нужно|надо|следует|раньше|ранее|когда-то|"
    r"ошибочно|ошибка|неверно|отменено|отменили|предыдущ\w*|прошл\w*)\b|"
    r"\b(?:при наличии|в случае|при условии|не исключено|по словам|со слов|"
    r"год назад|месяц назад|лет назад|не на|не в|не у|не только)\b",
    re.IGNORECASE,
)
CATEGORY_WORDS = {
    Category.LIGHTING: re.compile(r"^(?:ламп(?:а|у|ы|е|ой|очку|очка|очки)?|освещени[еяю]|свет(?:а|ом)?)$"),
    Category.ENTRANCE_DOOR: re.compile(r"^(?:двер(?:ь|и|ью|ей)|доводчик(?:а|у|ом)?)$"),
    Category.CLEANING: re.compile(r"^(?:уборк[ауи]|мусор(?:а)?|лестниц[ауеы]|пол(?:ы|а)?)$"),
}
DONE = frozenset(
    [
        "заменили",
        "заменена",
        "заменено",
        "заменены",
        "заменен",
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
        "убрана",
        "проведена",
        "провели",
        "вывезен",
        "вывезли",
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
        "горит",
        "вымыли",
        "вымыта",
        "вымыт",
        "помыли",
        "убрали",
    ]
)
FUTURE = frozenset(
    [
        "заменим",
        "починим",
        "сделаем",
        "установим",
        "выполним",
        "восстановим",
        "отрегулируем",
        "проведем",
        "уберем",
        "помоем",
    ]
)
PLAN = frozenset(["планируется", "планируем", "запланировано", "запланирована", "запланировали", "наметили"])
INFINITIVE = frozenset(
    [
        "заменить",
        "починить",
        "сделать",
        "установить",
        "выполнить",
        "восстановить",
        "отрегулировать",
        "провести",
        "убрать",
        "помыть",
    ]
)
PERSISTS = re.compile(
    r"\b(?:не\s+(?:(?:все\s+еще|так\s+и|по-прежнему)\s+)?(?:горит|работает|закрывается)|"
    r"света (?:пока )?нет|нет света|сломана|сломан|сломан[оы]|разбита|неисправна|неисправен|"
    r"работает (?:только )?иногда|работает нестабильно)\b"
)
BENIGN = re.compile(r"^(?:замечаний|жалоб)\s+не\s+(?:поступало|было)[.!]*$")
# Filler is intentionally finite. An unrecognised entity/predicate is not attached to
# the nearest known lamp. This trades coverage for a reviewable, conservative boundary.
FILLER = frozenset(
    [
        "на",
        "в",
        "во",
        "у",
        "по",
        "от",
        "для",
        "с",
        "со",
        "к",
        "ко",
        "за",
        "и",
        "а",
        "но",
        "однако",
        "зато",
        "все",
        "еще",
        "так",
        "пока",
        "теперь",
        "сейчас",
        "полностью",
        "сегодня",
        "завтра",
        "вход",
        "входной",
        "входную",
        "входная",
        "подъезд",
        "подъезде",
        "подъезда",
        "подъезду",
        "подьезд",
        "подьезда",
        "этаж",
        "этаже",
        "этажа",
        "этажи",
        "этажа",
        "первом",
        "первом",
        "втором",
        "третьем",
        "четвертом",
        "пятом",
        "шестом",
        "седьмом",
        "восьмом",
        "девятом",
        "десятом",
        "первый",
        "второй",
        "третий",
        "четвертый",
        "пятый",
        "шестой",
        "седьмой",
        "восьмой",
        "девятый",
        "десятый",
        "й",
        "м",
        "му",
        "го",
        "ом",
        "ый",
        "ой",
        "ая",
        "е",
        "номер",
        "лифта",
        "лифт",
        "лестничных",
        "клеток",
        "клетки",
        "влажная",
        "территорию",
        "территории",
        "двор",
        "дворе",
        "двора",
        "подвал",
        "подвале",
        "чердак",
        "чердаке",
        "входе",
        "после",
        "ремонта",
        "замены",
        "акт",
        "акту",
        "проверки",
        "нет",
        "не",
        "всегда",
        "только",
        "иногда",
        "нестабильно",
        "работы",
        "работу",
        "работ",
        "установку",
        "замена",
        "замену",
        "повторный",
        "выезд",
        "следующую",
        "неделю",
        "будет",
        "будем",
        "будут",
        "уже",
        "по-прежнему",
        "порядке",
    ]
)
ORDINALS = {
    word: number
    for number, forms in enumerate(
        (
            "первый первом первого первая первую",
            "второй втором второго вторая вторую",
            "третий третьем третьего третья третью",
            "четвертый четвертом четвертого четвертая четвертую",
            "пятый пятом пятого пятая пятую",
            "шестой шестом шестого шестая шестую",
            "седьмой седьмом седьмого седьмая седьмую",
            "восьмой восьмом восьмого восьмая восьмую",
            "девятый девятом девятого девятая девятую",
            "десятый десятом десятого десятая десятую",
        ),
        start=1,
    )
    for word in forms.split()
}
LANDMARKS = {"подвал": "подвал", "чердак": "чердак", "двор": "двор", "лифт": "лифт"}


@dataclass(frozen=True)
class Location:
    floor: int | None = None
    entrance: int | None = None
    landmark: str | None = None
    ambiguous: bool = False

    def specified(self):
        return any((self.floor is not None, self.entrance is not None, self.landmark, self.ambiguous))


@dataclass(frozen=True)
class Clause:
    start: int
    end: int
    category: Category | None
    location: Location
    relation: Relation | None
    unsafe: bool
    contrast: bool


def _text(text: str) -> str:
    return " ".join(text.lower().replace("ё", "е").split())


def _number(token: str) -> int | None:
    numeric = re.fullmatch(r"(\d+)(?:-(?:м|й|го|ом|ый|ой|ем))?", token)
    if numeric:
        digits = numeric.group(1)
        return int(digits) if len(digits) <= 6 else None
    return ORDINALS.get(token)


def _place_tokens(text: str, noun: str) -> list[str]:
    # Keep the entire signed/compound token. Extracting only digits would turn
    # -3, 3а, 3/4, 3.5 or 3,5 into a different floor. Trailing punctuation is harmless.
    token = r"([+\-−–—]?\s*(?:[^\s,;!?]|(?<=\d),(?=\d))+)"
    after = rf"\b{noun}[а-я]*\s*№?\s*{token}"
    before = rf"(?<!\S){token}\s+{noun}[а-я]*\b"
    return [
        m.group(1).strip().removeprefix("№").strip().rstrip(".,")
        for pattern in (after, before)
        for m in re.finditer(pattern, text)
    ]


def _invalid_place_token(token: str) -> bool:
    return any(character.isdigit() for character in token) and _number(token) is None


def _place_number(text: str, noun: str) -> tuple[int | None, bool]:
    tokens = _place_tokens(text, noun)
    values = {n for token in tokens if (n := _number(token)) is not None}
    invalid_number = any(_invalid_place_token(token) for token in tokens)
    return next(iter(values)) if len(values) == 1 else None, len(values) > 1 or invalid_number


def _location(text: str) -> Location:
    floor, floor_bad = _place_number(text, "этаж")
    entrance, entrance_bad = _place_number(text, "под[ъь]езд")
    landmarks = {value for stem, value in LANDMARKS.items() if re.search(rf"\b{stem}\w*\b", text)}
    ranges = bool(re.search(r"\d\s*[-–]\s*\d", text))
    return Location(
        floor,
        entrance,
        next(iter(landmarks)) if len(landmarks) == 1 else None,
        floor_bad or entrance_bad or len(landmarks) > 1 or ranges,
    )


def _merge_place(before: Location, current: Location) -> Location:
    return Location(
        current.floor if current.floor is not None else before.floor,
        current.entrance if current.entrance is not None else before.entrance,
        current.landmark or before.landmark,
        current.ambiguous,
    )


def _categories(tokens: list[str]) -> set[Category]:
    return {
        category for category, pattern in CATEGORY_WORDS.items() if any(pattern.fullmatch(t) for t in tokens)
    }


def _classify(text: str, tokens: list[str]) -> tuple[Relation | None, bool]:
    """Only recognised predicates with a locally resolved polarity may assert a result."""
    if BENIGN.fullmatch(text) or not tokens or tokens == ["отчет", "готов"]:
        return None, False
    allowed = DONE | FUTURE | PLAN | INFINITIVE | FILLER
    unknown = [
        t
        for t in tokens
        if not t.isdigit()
        and t not in allowed
        and not _categories([t])
        and _number(t) is None
        and not PERSISTS.fullmatch(t)
    ]
    if unknown:
        return None, True
    if PERSISTS.search(text):
        return Relation.SYMPTOM_PERSISTS, False
    if any(t in tokens for t in ("не", "нет", "почти", "частично", "иногда", "только")):
        return None, True
    completed = bool(DONE.intersection(tokens))
    planned = bool(
        FUTURE.intersection(tokens) or (PLAN.intersection(tokens) and INFINITIVE.intersection(tokens))
    )
    if completed and (planned or "завтра" in tokens or any(t in tokens for t in ("будет", "будем", "будут"))):
        return None, True
    relation = (Relation.REPORTED_DONE if completed else Relation.PLANNED) if completed or planned else None
    return relation, relation is None and bool(PLAN.intersection(tokens))


def _spans(text: str, start: int, end: int):
    cursor = start
    for match in CUTS.finditer(text, start, end):
        if cursor < match.start():
            yield cursor, match.start()
        cursor = match.end()
    if cursor < end:
        yield cursor, end


def _clauses(text: str) -> list[Clause]:
    clauses = []
    previous_place = Location()
    for match in SENTENCES.finditer(text):
        subject = None
        place = previous_place
        for start, end in _spans(text, match.start(), match.end()):
            while start < end and text[start].isspace():
                start += 1
            while end > start and text[end - 1].isspace():
                end -= 1
            fragment = _text(text[start:end])
            categories = _categories(WORDS.findall(fragment))
            current_place = _location(fragment)
            inherited = any(
                earlier is not None and current is None
                for earlier, current in (
                    (previous_place.floor, current_place.floor),
                    (previous_place.entrance, current_place.entrance),
                    (previous_place.landmark, current_place.landmark),
                )
            ) or (place.specified() and subject is None and not current_place.specified())
            place = _merge_place(place, current_place)
            if categories:
                subject = next(iter(categories)) if len(categories) == 1 else None
            relation, unsafe = _classify(fragment, WORDS.findall(fragment))
            for category in categories or {subject}:
                clauses.append(
                    Clause(
                        start,
                        end,
                        category,
                        place,
                        relation,
                        unsafe
                        or len(categories) > 1
                        or place.ambiguous
                        or bool(inherited)
                        # A lift/basement door is not necessarily the entrance door.
                        # The bounded grammar cannot resolve this object subtype.
                        or (category == Category.ENTRANCE_DOOR and place.landmark is not None),
                        bool(re.match(r"(?:но|однако|зато)\b", fragment)),
                    )
                )
        previous_place = place
    return clauses


def _conflicts(item: Location, clause: Location) -> bool:
    return any(
        a is not None and b is not None and a != b
        for a, b in (
            (item.floor, clause.floor),
            (item.entrance, clause.entrance),
            (item.landmark, clause.landmark),
        )
    )


def _evidence(text: str, clauses: list[Clause]) -> Evidence:
    start, end = clauses[0].start, clauses[-1].end
    return Evidence(start=start, end=end, quote=text[start:end])


def _resolve(text: str, claims: list[Clause], all_clauses: list[Clause]):
    if not claims:
        return ABSTAIN
    relations = {c.relation for c in claims}
    if len(relations) == 1:
        return claims[0].relation, _evidence(text, [claims[0]])
    if relations == {Relation.REPORTED_DONE, Relation.SYMPTOM_PERSISTS}:
        start, end = claims[0].start, claims[-1].end
        # One contiguous quote must show both sides without importing another subject.
        if any(c.category not in (None, claims[0].category) for c in all_clauses if start < c.start < end):
            return ABSTAIN
        persistence = any(c.contrast and c.relation == Relation.SYMPTOM_PERSISTS for c in claims)
        return (Relation.SYMPTOM_PERSISTS if persistence else Relation.CONFLICT), _evidence(text, claims)
    return ABSTAIN


def _analyze(request: CompareRequest) -> list[tuple[Relation, Evidence | None]]:
    if UNSUPPORTED_SCOPE.search(request.reportText) or any(
        _invalid_place_token(token)
        for noun in ("этаж", "под[ъь]езд")
        for token in _place_tokens(_text(request.reportText), noun)
    ):
        # Inspect before sentence splitting, which would otherwise split a decimal
        # location and lose the unsupported place prefix before the claimed result.
        return [ABSTAIN for _ in request.items]
    clauses = _clauses(request.reportText)
    # Unassigned prose can scope a later assertion (instructions, attribution or
    # retraction). Do not silently discard it and promote its embedded quote to fact.
    if any(c.unsafe and c.category is None for c in clauses):
        return [ABSTAIN for _ in request.items]
    locations = [_location(_text(item.location)) for item in request.items]
    outcomes = []
    for index, item in enumerate(request.items):
        outcomes.append(_analyze_target(request, index, item, locations, clauses))
    return outcomes


def _analyze_target(request, index, item, locations, clauses):
    claims, elsewhere = [], []
    for clause in clauses:
        if clause.category != item.category:
            continue
        if clause.unsafe:
            return ABSTAIN
        if clause.relation is None:
            continue
        if _conflicts(locations[index], clause.location):
            elsewhere.append(replace(clause, relation=Relation.UNRELATED))
            continue
        candidates = [
            i
            for i, other in enumerate(request.items)
            if other.category == item.category and not _conflicts(locations[i], clause.location)
        ]
        if len(candidates) != 1 or locations[index].ambiguous:
            return ABSTAIN
        claims.append(clause)
    if claims:
        return _resolve(request.reportText, claims, clauses)
    return _resolve(request.reportText, elsewhere, clauses)


def analyze_rules(request: CompareRequest) -> CompareResponse:
    """Advisory interpretation; request scope, not item ordering, resolves the subject."""
    outcomes = _analyze(request)
    return CompareResponse(
        caseId=request.caseId,
        sourceRevision=request.sourceRevision,
        reportId=request.reportId,
        modelVersion=RULES_MODEL_VERSION,
        mode=Mode.RULES,
        suggestions=[
            Suggestion(
                relation=relation,
                evidence=evidence,
                reason=Reason.ANALYZED,
                itemId=item.itemId,
                contentVersion=item.contentVersion,
                workRound=item.workRound,
            )
            for item, (relation, evidence) in zip(request.items, outcomes, strict=True)
        ],
    )
