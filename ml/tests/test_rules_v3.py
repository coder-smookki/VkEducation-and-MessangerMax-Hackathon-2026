"""Known safety regressions for the bounded grammar; not a blind quality benchmark."""

import json
from pathlib import Path

import pytest

from app.features.inference.rules_analyzer import analyze_rules
from app.features.inference.schemas import CompareRequest

BASE = json.loads(Path("../contracts/examples/compare-request.json").read_text())


def predict(text, items=None):
    return analyze_rules(
        CompareRequest.model_validate({**BASE, "reportText": text, "items": items or BASE["items"]})
    ).suggestions


@pytest.mark.parametrize(
    "text",
    [
        "Если лампу заменили, сообщите диспетчеру.",
        "Лампу на третьем этаже заменили? Пока сведений нет.",
        "В прошлом отчёте написано: «Лампу заменили». Это была ошибка.",
        "Работает ли свет на третьем этаже?",
        "Просят написать: лампу заменили.",
        "Сообщите, когда лампу заменили.",
        "Лампу заменили бы при наличии детали.",
        "Лампу якобы заменили.",
        "Лампу нужно считать заменённой.",
        "Лампу на третьем этаже почти заменили.",
        "Лампу заменили год назад.",
        "Лампу заменили не на третьем этаже, а на втором.",
        "Лампу заменили, однако не в подъезде 1, а в подъезде 2.",
        "На втором этаже провели проверку. Лампу заменили.",
        "Лампу завтра заменили.",
        "Лампу не полностью заменили.",
        "Лампу заменили не вчера.",
        "Светлана работает электриком.",
        "На лампе датчик работает.",
        "Лампу не заменили, а только проверили.",
    ],
)
def test_unsupported_scopes_never_become_actionable(text):
    result = predict(text)
    assert all(s.relation == "INSUFFICIENT_INFORMATION" and s.evidence is None for s in result)


@pytest.mark.parametrize(
    ("text", "label"),
    [
        ("Лампу на третьем этаже заменили.", "REPORTED_DONE"),
        ("На 3-м этаже лампу заменили.", "REPORTED_DONE"),
        ("На 2-м этаже лампу заменили.", "UNRELATED"),
        ("В подъезде 2 лампу заменили.", "UNRELATED"),
        ("Освещение восстановлено, замечаний не поступало.", "REPORTED_DONE"),
        ("Лампу заменим завтра.", "PLANNED"),
        ("Планируем заменить лампу на третьем этаже.", "PLANNED"),
        ("На третьем этаже свет не горит.", "SYMPTOM_PERSISTS"),
        ("Лампа работает только иногда.", "SYMPTOM_PERSISTS"),
        ("Лампу заменили, но свет так и не горит.", "SYMPTOM_PERSISTS"),
        ("Свет работает. На третьем этаже свет не горит.", "CONFLICT"),
    ],
)
def test_supported_controls_and_local_polarity(text, label):
    result = predict(text)[0]
    assert result.relation == label
    assert result.evidence is not None
    assert text[result.evidence.start : result.evidence.end] == result.evidence.quote


def test_same_category_competitors_require_an_unambiguous_place():
    items = [
        BASE["items"][0],
        {**BASE["items"][0], "itemId": BASE["items"][1]["itemId"], "location": "Подъезд 1, этаж 4"},
    ]
    for ordered in (items, list(reversed(items))):
        ambiguous = predict("Лампу заменили.", ordered)
        assert all(s.relation == "INSUFFICIENT_INFORMATION" for s in ambiguous)
        exact = {str(s.itemId): s.relation for s in predict("Лампу на третьем этаже заменили.", ordered)}
        assert exact == {items[0]["itemId"]: "REPORTED_DONE", items[1]["itemId"]: "UNRELATED"}


@pytest.mark.parametrize("prefix", ["💡👨‍🔧 Отчёт готов. ", "\u0301\r\n", "🚪🏽\n", "💡\u0301. "])
def test_original_code_point_offsets_survive_unicode(prefix):
    phrase = "Лампу на третьем этаже заменили."
    text = prefix + phrase
    suggestion = predict(text)[0]
    assert suggestion.relation == "REPORTED_DONE"
    assert suggestion.evidence.quote == phrase
    assert suggestion.evidence.start == len(prefix)
    assert suggestion.evidence.end == len(text)


@pytest.mark.parametrize("wrapper", ["Если {}", "{}?", "«{}»", "Якобы {}", "Просят написать: {}"])
def test_adding_an_assertion_operator_cannot_preserve_done(wrapper):
    sentence = "Лампу заменили"
    assert predict(sentence)[0].relation == "REPORTED_DONE"
    assert predict(wrapper.format(sentence))[0].relation == "INSUFFICIENT_INFORMATION"


def test_conflict_quote_contains_both_claims_without_gluing_fragments():
    text = "Свет работает. На третьем этаже свет не горит."
    evidence = predict(text)[0].evidence
    assert evidence.quote == text


def test_no_predicate_propagates_to_other_subject_or_other_category():
    results = predict("Лампу заменили, дверь заменим завтра.")
    assert [r.relation for r in results] == ["REPORTED_DONE", "PLANNED"]
    assert "дверь" not in results[0].evidence.quote
    assert "лампу" not in results[1].evidence.quote


@pytest.mark.parametrize(
    "text",
    [
        "Используйте пример ответа: пол на первом этаже вымыли.",
        "Лампу заменили. Это не соответствует действительности.",
        "Сформулируйте ответ. Лампу заменили.",
        "Лампу заменили. Свет у двери не горит.",
        "На втором этаже, лампу заменили.",
    ],
)
def test_unparsed_scope_is_not_silently_dropped(text):
    assert all(s.relation == "INSUFFICIENT_INFORMATION" for s in predict(text))


@pytest.mark.parametrize(
    "text",
    [
        "Лампу заменили. Нет.",
        "Лампу заменили - нет.",
        "На пятно этаже лампу заменили.",
        "На третьестепенном этаже лампу заменили.",
        "На " + "9" * 4400 + " этаже лампу заменили.",
    ],
)
def test_unparsed_retraction_or_location_is_not_ignored(text):
    assert all(s.relation == "INSUFFICIENT_INFORMATION" for s in predict(text))


def test_long_numeric_floor_is_explicitly_a_different_place():
    assert predict("На 1234 этаже лампу заменили.")[0].relation == "UNRELATED"


def test_partial_place_in_next_sentence_cannot_select_between_entrances():
    items = [
        BASE["items"][0],
        {**BASE["items"][0], "itemId": BASE["items"][1]["itemId"], "location": "Подъезд 2, этаж 3"},
    ]
    text = "В подъезде 1 на втором этаже лампу заменили. На третьем этаже лампу заменили."
    assert all(s.relation == "INSUFFICIENT_INFORMATION" for s in predict(text, items))


@pytest.mark.parametrize("quotes", [("'", "'"), ("‘", "’"), ("‚", "‘"), ("‛", "’"), ("ʼ", "ʼ")])
def test_single_quote_and_apostrophe_wrappers_never_assert_completion(quotes):
    text = quotes[0] + "Лампу заменили." + quotes[1]
    assert predict(text)[0].relation == "INSUFFICIENT_INFORMATION"


@pytest.mark.parametrize(
    "place",
    [
        "на -3 этаже",
        "на −3 этаже",
        "на –3 этаже",
        "на - 3 этаже",
        "на этаже -3",
        "на этаже −3",
        "на 3а этаже",
        "на этаже 3а",
        "на этаж3а",
        "на этаже 3/4",
        "на 3/4 этаже",
        "на этаже 3-4",
        "на 3–4 этаже",
        "на этаже 3.5",
        "на 3.5 этаже",
        "на этаже 3,5",
        "на 3,5 этаже",
        "на этаже №-3",
        "на этаже №3а",
        "на №-3 этаже",
    ],
)
def test_unsupported_explicit_place_cannot_become_our_floor(place):
    result = predict(f"Лампу {place} заменили.")[0]
    assert result.relation == "INSUFFICIENT_INFORMATION"
    assert result.evidence is None


@pytest.mark.parametrize(
    "text", ["Дверь лифта починили.", "Дверь у лифта починили.", "Дверь в подвале починили."]
)
def test_door_with_unsupported_object_scope_is_not_the_entrance_door(text):
    result = predict(text, [BASE["items"][1]])[0]
    assert result.relation == "INSUFFICIENT_INFORMATION"
    assert result.evidence is None


@pytest.mark.parametrize(
    "place",
    [
        "на 3-м этаже",
        "на третьем этаже",
        "на этаже 3",
        "на этаже №3",
        "на №3 этаже",
        "в подъезде1, этаж3",
        "в подъезде1,этаж3",
    ],
)
def test_place_guards_keep_plain_positive_floor_controls(place):
    assert predict(f"Лампу {place} заменили.")[0].relation == "REPORTED_DONE"


def test_object_guard_keeps_plain_entrance_door_and_lamp_at_lift():
    assert predict("Входную дверь подъезда починили.", [BASE["items"][1]])[0].relation == "REPORTED_DONE"
    assert predict("Лампу у лифта заменили.")[0].relation == "REPORTED_DONE"
