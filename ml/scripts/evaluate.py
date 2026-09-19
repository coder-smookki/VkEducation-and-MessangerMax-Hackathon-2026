"""Evaluate every available analyzer on development examples; never a held-out quality claim."""

import json
from collections.abc import Callable
from datetime import date
from pathlib import Path

from app.features.inference.rules_analyzer import analyze_rules
from app.features.inference.schemas import CompareRequest, CompareResponse, Relation
from app.features.inference.service import InferenceService

DATA_PATH = Path("data/examples.jsonl")
RESULTS_DIR = Path("results")

ANALYZERS: dict[str, Callable[[CompareRequest], CompareResponse]] = {
    "stub": InferenceService.analyze,
    "rules": analyze_rules,
}

ACTIONABLE = {Relation.REPORTED_DONE.value, Relation.PLANNED.value}
NOTE = "Development examples only; no held-out quality claim. Missing precision is not 100%."


def load_rows(path: Path = DATA_PATH) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def gold_label(entry) -> str:
    """Both formats stay valid: plain label string or {label, evidence} object."""
    return entry["label"] if isinstance(entry, dict) else entry


def _ratio(numerator: int, denominator: int) -> float | None:
    return numerator / denominator if denominator else None


def _evaluate_analyzer(
    name: str, analyze: Callable[[CompareRequest], CompareResponse], rows: list[dict]
) -> dict:
    records = []
    modes = set()
    model_versions = set()
    for row in rows:
        request = CompareRequest.model_validate(row["request"])
        response = analyze(request)
        modes.add(response.mode.value)
        model_versions.add(response.modelVersion)
        for suggestion in response.suggestions:
            evidence_valid = None
            if suggestion.evidence is not None:
                span = suggestion.evidence
                evidence_valid = request.reportText[span.start : span.end] == span.quote
            gold = gold_label(row["expected"][str(suggestion.itemId)])
            substantive = suggestion.relation != Relation.INSUFFICIENT_INFORMATION
            records.append(
                {
                    "dialogId": row["id"],
                    "templateGroup": row["templateGroup"],
                    "split": row.get("split", "development"),
                    "itemId": str(suggestion.itemId),
                    "gold": gold,
                    "prediction": suggestion.relation.value,
                    "correct": (suggestion.relation.value == gold) if substantive else None,
                    "evidence": (
                        None
                        if suggestion.evidence is None
                        else {
                            "start": suggestion.evidence.start,
                            "end": suggestion.evidence.end,
                            "quote": suggestion.evidence.quote,
                        }
                    ),
                    "evidenceValid": evidence_valid,
                }
            )
    return {
        "analyzer": name,
        "modes": sorted(modes),
        "modelVersion": sorted(model_versions),
        "metrics": _metrics(records),
        "records": records,
    }


def _metrics(records: list[dict]) -> dict:
    total = len(records)
    substantive = [r for r in records if r["prediction"] != Relation.INSUFFICIENT_INFORMATION.value]
    correct = [r for r in substantive if r["correct"]]
    actionable = [r for r in records if r["prediction"] in ACTIONABLE]
    actionable_correct = [r for r in actionable if r["prediction"] == r["gold"]]
    with_evidence = [r for r in records if r["evidence"] is not None]
    valid_evidence = [r for r in with_evidence if r["evidenceValid"]]
    return {
        "items": total,
        "substantive": len(substantive),
        "coverage": _ratio(len(substantive), total) if total else 0,
        "labelPrecision": {
            "numerator": len(correct),
            "denominator": len(substantive),
            "value": _ratio(len(correct), len(substantive)),
        },
        "actionablePrecision": {
            "numerator": len(actionable_correct),
            "denominator": len(actionable),
            "value": _ratio(len(actionable_correct), len(actionable)),
        },
        "evidenceValidity": {
            "numerator": len(valid_evidence),
            "denominator": len(with_evidence),
            "value": _ratio(len(valid_evidence), len(with_evidence)),
        },
    }


def main(results_dir: Path = RESULTS_DIR) -> list[Path]:
    rows = load_rows()
    results_dir.mkdir(parents=True, exist_ok=True)
    today = date.today().isoformat()  # noqa: DTZ011  # a calendar date in the file name is enough
    written = []
    summary = {"dataset": "synthetic-development-examples", "note": NOTE, "analyzers": {}}
    for name, analyze in ANALYZERS.items():
        outcome = _evaluate_analyzer(name, analyze, rows)
        version = "-".join(outcome["modelVersion"]) or "unknown"
        path = results_dir / f"eval-{name}-{version}-{today}.json"
        path.write_text(json.dumps(outcome, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        written.append(path)
        summary["analyzers"][name] = {
            "modes": outcome["modes"],
            "modelVersion": outcome["modelVersion"],
            "metrics": outcome["metrics"],
        }
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return written


if __name__ == "__main__":
    main()
