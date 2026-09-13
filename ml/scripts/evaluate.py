"""Wiring baseline on development examples only; never a held-out quality claim."""

import json
from pathlib import Path

from app.features.inference.schemas import CompareRequest, Relation
from app.features.inference.service import InferenceService

rows = [
    json.loads(line)
    for line in Path("data/examples.jsonl").read_text(encoding="utf-8").splitlines()
    if line.strip()
]
total = suggested = correct = 0
modes = set()
model_versions = set()
for row in rows:
    response = InferenceService.analyze(CompareRequest.model_validate(row["request"]))
    modes.add(response.mode.value)
    model_versions.add(response.modelVersion)
    for item in response.suggestions:
        total += 1
        if item.relation != Relation.INSUFFICIENT_INFORMATION:
            suggested += 1
            correct += item.relation.value == row["expected"][str(item.itemId)]
print(
    json.dumps(
        {
            "dataset": "synthetic-development-examples",
            "modes": sorted(modes),
            "modelVersions": sorted(model_versions),
            "items": total,
            "suggested": suggested,
            "precision": correct / suggested if suggested else None,
            "coverage": suggested / total if total else 0,
            "note": "Development examples only; no held-out quality claim. Missing precision is not 100%.",
        },
        ensure_ascii=False,
        indent=2,
    )
)
