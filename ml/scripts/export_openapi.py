import json
from pathlib import Path

from app.features.intake.contracts import ConfirmedCaseContext
from app.main import app

target = Path(__file__).resolve().parents[2] / "contracts/inference.openapi.json"
target.parent.mkdir(exist_ok=True)
target.write_text(json.dumps(app.openapi(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(target)
context_target = target.parent / "confirmed-context.schema.json"
context_target.write_text(
    json.dumps(
        {
            "$schema": "https://json-schema.org/draft/2020-12/schema",
            **ConfirmedCaseContext.model_json_schema(),
        },
        ensure_ascii=False,
        indent=2,
    )
    + "\n",
    encoding="utf-8",
)
print(context_target)
