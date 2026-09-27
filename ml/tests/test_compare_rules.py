"""Comparison accounting: strict and sensitivity results remain distinct."""

from scripts.compare_rules import _extended_metrics, compare
from scripts.evaluate import load_rows


def test_false_done_counter_cannot_be_hidden_by_exact_quotes():
    row = {
        "gold": "INSUFFICIENT_INFORMATION",
        "prediction": "REPORTED_DONE",
        "correct": False,
        "evidence": {"start": 0, "end": 6, "quote": "example"},
        "evidenceValid": True,
    }
    metrics = _extended_metrics([row])
    assert metrics["falseReportedDone"] == 1
    assert metrics["labelPrecision"]["value"] == 0
    assert metrics["evidenceValidity"]["value"] == 1


def test_disputed_alternative_does_not_overwrite_strict_gold():
    row = load_rows()[0]
    row["request"]["reportText"] = "Лампу заменили."
    lighting_id = next(i["itemId"] for i in row["request"]["items"] if i["category"] == "LIGHTING")
    row["expected"] = {i["itemId"]: "INSUFFICIENT_INFORMATION" for i in row["request"]["items"]}
    row["disputed"] = True
    row["acceptableAlternatives"] = {lighting_id: ["REPORTED_DONE"]}
    results = compare([row])
    for name in ("v2", "guarded-v2", "scoped-v3"):
        data = results[name]
        assert data["metrics"]["falseReportedDone"] == 1
        assert data["acceptingAlternatives"]["falseReportedDone"] == 0
        assert data["excludingDisputed"]["items"] == 0
        assert data["excludingDisputed"]["labelPrecision"]["value"] is None
    assert row["expected"][lighting_id] == "INSUFFICIENT_INFORMATION"
