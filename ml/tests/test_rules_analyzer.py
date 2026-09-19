"""Tests for the deterministic RULES baseline; development wiring, not an NLP-quality claim."""

import pytest

from app.core.config import Settings
from app.features.inference.rules_analyzer import RULES_MODEL_VERSION, analyze_rules
from app.features.inference.schemas import CompareRequest, CompareResponse, Relation
from app.features.inference.service import InferenceService

ITEM_ID = "00000000-0000-0000-0000-000000000004"
DOOR_ID = "00000000-0000-0000-0000-000000000005"


def make_request(text, items=None):
    items = items or [
        {
            "itemId": ITEM_ID,
            "contentVersion": 2,
            "workRound": 3,
            "category": "LIGHTING",
            "location": "Подъезд 1, этаж 3",
            "symptom": "Не горит свет",
        }
    ]
    return CompareRequest.model_validate(
        {
            "caseId": "00000000-0000-0000-0000-000000000001",
            "sourceRevision": 4,
            "reportId": "00000000-0000-0000-0000-000000000003",
            "reportText": text,
            "items": items,
        }
    )


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("Лампу на третьем этаже заменили, освещение работает.", Relation.REPORTED_DONE),
        ("Лампу на третьем этаже заменим завтра.", Relation.PLANNED),
        ("Лампу на третьем этаже заменили, но света пока нет.", Relation.SYMPTOM_PERSISTS),
        ("На втором этаже освещение восстановили.", Relation.UNRELATED),
        ("Замечаний нет.", Relation.INSUFFICIENT_INFORMATION),
        (
            (
                "Освещение на третьем этаже восстановлено, свет работает. "
                "По акту проверки свет на третьем этаже не горит."
            ),
            Relation.CONFLICT,
        ),
    ],
)
def test_each_relation_is_detected(text, expected):
    suggestion = analyze_rules(make_request(text)).suggestions[0]
    assert suggestion.relation == expected
    if expected == Relation.INSUFFICIENT_INFORMATION:
        assert suggestion.evidence is None
    else:
        assert suggestion.evidence is not None


def test_abstains_when_report_is_uninformative():
    request = make_request("Принято в работу, детали уточняются.")
    suggestion = analyze_rules(request).suggestions[0]
    assert suggestion.relation == Relation.INSUFFICIENT_INFORMATION
    assert suggestion.evidence is None


def test_first_number_is_not_guessed_as_floor():
    text = "Заменили 3 лампы в подъезде 2 на пятом этаже."
    suggestion = analyze_rules(make_request(text)).suggestions[0]
    assert suggestion.relation == Relation.UNRELATED
    assert suggestion.evidence.quote == text


def test_emoji_before_fragment_keeps_code_point_offsets():
    text = "Отчёт 💡🔧 готов. Лампу в подъезде заменили."
    fragment = "Лампу в подъезде заменили."
    suggestion = analyze_rules(make_request(text)).suggestions[0]
    evidence = suggestion.evidence
    assert suggestion.relation == Relation.REPORTED_DONE
    # Python str indexes are Unicode code points: each emoji is one unit, not two UTF-16 units.
    assert evidence.start == text.index(fragment)
    assert text[evidence.start : evidence.end] == evidence.quote == fragment


def test_versions_and_ids_are_echoed_with_one_suggestion_per_item():
    items = [
        {
            "itemId": ITEM_ID,
            "contentVersion": 7,
            "workRound": 3,
            "category": "LIGHTING",
            "location": "Подъезд 1, этаж 3",
            "symptom": "Не горит свет",
        },
        {
            "itemId": DOOR_ID,
            "contentVersion": 5,
            "workRound": 0,
            "category": "ENTRANCE_DOOR",
            "location": "Подъезд 1, вход",
            "symptom": "Дверь не закрывается",
        },
    ]
    request = make_request("Лампу на третьем этаже заменили.", items)
    response = analyze_rules(request)
    assert response.caseId == request.caseId
    assert response.sourceRevision == request.sourceRevision
    assert response.reportId == request.reportId
    assert response.mode == "RULES"
    assert response.modelVersion == RULES_MODEL_VERSION
    assert [str(s.itemId) for s in response.suggestions] == [ITEM_ID, DOOR_ID]
    assert {(s.contentVersion, s.workRound) for s in response.suggestions} == {(7, 3), (5, 0)}
    CompareResponse.model_validate(response.model_dump())


def test_stub_remains_the_default_and_rules_is_opt_in():
    request = make_request("Лампу на третьем этаже заменили.")
    stub = InferenceService(None).compare(request)
    assert stub.mode == "STUB"
    assert stub.modelVersion == "stub-v1"
    assert all(s.relation == Relation.INSUFFICIENT_INFORMATION for s in stub.suggestions)
    rules = InferenceService(None, Settings(compare_analyzer="rules")).compare(request)
    assert rules.mode == "RULES"
    assert rules.modelVersion == RULES_MODEL_VERSION
    assert rules.suggestions[0].relation == Relation.REPORTED_DONE
