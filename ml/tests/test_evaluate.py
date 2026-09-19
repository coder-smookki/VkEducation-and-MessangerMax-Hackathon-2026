"""Evaluator protocol tests: both analyzers run, machine results are written."""

import json

from app.features.inference.schemas import CompareRequest
from scripts import evaluate


def test_gold_format_is_backward_compatible():
    assert evaluate.gold_label("PLANNED") == "PLANNED"
    assert evaluate.gold_label({"label": "PLANNED", "evidence": []}) == "PLANNED"


def test_dataset_gold_evidence_is_exact():
    rows = evaluate.load_rows()
    for row in rows:
        text = row["request"]["reportText"]
        for entry in row["expected"].values():
            if isinstance(entry, dict):
                for span in entry.get("evidence", []):
                    assert text[span["start"] : span["end"]] == span["quote"]
        CompareRequest.model_validate(row["request"])


def test_evaluator_writes_machine_results(tmp_path):
    written = evaluate.main(results_dir=tmp_path)
    assert {p.name.split("-")[1] for p in written} == {"stub", "rules"}
    for path in written:
        data = json.loads(path.read_text(encoding="utf-8"))
        assert data["records"] and data["metrics"]["items"] == 29
        assert {"coverage", "labelPrecision", "actionablePrecision", "evidenceValidity"} <= set(
            data["metrics"]
        )


def test_metrics_are_honest_for_both_analyzers(tmp_path):
    written = evaluate.main(results_dir=tmp_path)
    by_analyzer = {json.loads(p.read_text(encoding="utf-8"))["analyzer"]: p for p in written}
    stub = json.loads(by_analyzer["stub"].read_text(encoding="utf-8"))
    assert stub["metrics"]["coverage"] == 0
    assert stub["metrics"]["labelPrecision"]["value"] is None
    rules = json.loads(by_analyzer["rules"].read_text(encoding="utf-8"))
    metrics = rules["metrics"]
    assert metrics["coverage"] > 0
    assert metrics["labelPrecision"]["denominator"] == metrics["substantive"]
    assert metrics["evidenceValidity"]["value"] == 1.0
