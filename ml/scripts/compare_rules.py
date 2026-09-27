"""Reproduce three alternatives on one labelled synthetic dataset; never a human holdout claim."""

import argparse
import hashlib
import json
import re
from collections import Counter
from pathlib import Path
from statistics import median
from time import perf_counter_ns

from app.features.inference.rules_analyzer import analyze_rules
from app.features.inference.schemas import CompareRequest, Relation
from scripts.evaluate import _metrics, gold_label, load_rows
from scripts.evaluation.rules_v2 import analyze_rules as analyze_v2

MINIMAL_GUARD = re.compile(r'[?«»"“”]|\b(?:если|бы)\b', re.IGNORECASE)


def guarded_v2(request):
    """The tempting small patch: block whole conditional, question or quoted reports."""
    response = analyze_v2(request)
    suggestions = response.suggestions
    if MINIMAL_GUARD.search(request.reportText):
        suggestions = [
            s.model_copy(update={"relation": Relation.INSUFFICIENT_INFORMATION, "evidence": None})
            for s in suggestions
        ]
    return response.model_copy(
        update={"modelVersion": "guarded-v2-comparison-only", "suggestions": suggestions}
    )


ANALYZERS = {"v2": analyze_v2, "guarded-v2": guarded_v2, "scoped-v3": analyze_rules}


def _measure(rows, analyze):
    records, durations = [], []
    for row in rows:
        request = CompareRequest.model_validate(row["request"])
        started = perf_counter_ns()
        response = analyze(request)
        durations.append((perf_counter_ns() - started) / 1_000_000)
        assert {str(s.itemId) for s in response.suggestions} == {str(i.itemId) for i in request.items}
        assert len(response.suggestions) == len(request.items)
        for suggestion in response.suggestions:
            evidence = suggestion.evidence
            predicted = suggestion.relation.value
            gold = gold_label(row["expected"][str(suggestion.itemId)])
            records.append(
                {
                    "dialogId": row["id"],
                    "templateGroup": row["templateGroup"],
                    "itemId": str(suggestion.itemId),
                    "gold": gold,
                    "prediction": predicted,
                    "disputed": row.get("disputed", False),
                    "alternatives": row.get("acceptableAlternatives", {}).get(str(suggestion.itemId), []),
                    "correct": predicted == gold if predicted != "INSUFFICIENT_INFORMATION" else None,
                    "evidence": evidence.model_dump() if evidence else None,
                    "evidenceValid": request.reportText[evidence.start : evidence.end] == evidence.quote
                    if evidence
                    else None,
                }
            )
    return records, durations


def _extended_metrics(records):
    metrics = _metrics(records)
    confusion = Counter((r["gold"], r["prediction"]) for r in records)
    per_class = {}
    for relation in Relation:
        selected = [r for r in records if r["prediction"] == relation.value]
        hits = sum(r["prediction"] == r["gold"] for r in selected)
        per_class[relation.value] = {
            "predicted": len(selected),
            "correct": hits,
            "precision": hits / len(selected) if selected else None,
        }
    metrics.update(
        {
            "falseReportedDone": sum(
                r["prediction"] == "REPORTED_DONE" and r["gold"] != "REPORTED_DONE" for r in records
            ),
            "falsePlanned": sum(r["prediction"] == "PLANNED" and r["gold"] != "PLANNED" for r in records),
            "perClass": per_class,
            "confusion": [
                {"gold": gold, "predicted": prediction, "count": count}
                for (gold, prediction), count in sorted(confusion.items())
            ],
        }
    )
    return metrics


def compare(rows):
    """Every variant receives identical inputs; disputed labels never silently replace strict metrics."""
    outputs = {}
    for name, analyze in ANALYZERS.items():
        records, durations = _measure(rows, analyze)
        sensitivity = [
            {**r, "gold": r["prediction"], "correct": True} if r["prediction"] in r["alternatives"] else r
            for r in records
        ]
        outputs[name] = {
            "metrics": _extended_metrics(records),
            "records": records,
            "excludingDisputed": _extended_metrics([r for r in records if not r["disputed"]]),
            "acceptingAlternatives": _extended_metrics(sensitivity),
            "localTimingMs": {
                "p50": median(durations),
                "p95": sorted(durations)[int((len(durations) - 1) * 0.95)],
            },
        }
    return outputs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    rows = load_rows(args.dataset)
    if not rows:
        parser.error("The dataset must not be empty")
    result = {
        "note": (
            "Synthetic developer/agent challenge, not independent human holdout. Exact spans do not "
            "prove semantic adequacy. In-process local timings are not service/server latency."
        ),
        "dataset": str(args.dataset),
        "datasetSha256": hashlib.sha256(args.dataset.read_bytes()).hexdigest(),
        "sourceSha256": {
            str(path): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in (
                Path("app/features/inference/rules_analyzer.py"),
                Path("scripts/evaluation/rules_v2.py"),
                Path(__file__),
            )
        },
        "dialogs": len(rows),
        "templateGroups": len({r["templateGroup"] for r in rows}),
        "analyzers": compare(rows),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(
        json.dumps(
            {name: data["metrics"] for name, data in result["analyzers"].items()},
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
