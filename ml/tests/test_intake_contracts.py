import copy
import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from jsonschema import Draft202012Validator, FormatChecker
from pydantic import ValidationError

from app.features.intake.contracts import ConfirmedCaseContext, ExtractItemsResponse
from app.http_errors import MAX_REQUEST_BYTES
from app.main import app

ROOT = Path(__file__).resolve().parents[2] / "contracts"
client = TestClient(app)


def fixture(name):
    return json.loads((ROOT / "examples" / name).read_text())


def validate_schema(document, name, value):
    # Resolve only references inside the committed document; no network resolution.
    schema = {"$ref": f"#/components/schemas/{name}", "components": document["components"]}
    Draft202012Validator(schema, format_checker=FormatChecker()).validate(value)


@pytest.mark.parametrize(
    "route,name",
    [
        ("extract-items", "extract"),
        ("clarify-items", "clarify"),
        ("similar-cases", "similar"),
        ("compare", "compare"),
    ],
)
def test_real_response_and_input_match_committed_contract(route, name):
    api = json.loads((ROOT / "inference.openapi.json").read_text())
    operation = api["paths"][f"/internal/v1/{route}"]["post"]
    request_name = operation["requestBody"]["content"]["application/json"]["schema"]["$ref"].split("/")[-1]
    response_name = operation["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].split("/")[
        -1
    ]
    payload = fixture(f"{name}-request.json")
    validate_schema(api, request_name, payload)
    response = client.post(f"/internal/v1/{route}", json=payload)
    assert response.status_code == 200
    assert response.json() == fixture(f"{name}-response.json")
    validate_schema(api, response_name, response.json())


@pytest.mark.parametrize("route", ["extract-items", "clarify-items", "similar-cases", "compare"])
def test_actual_422_matches_openapi_and_never_echoes_input(route):
    response = client.post(f"/internal/v1/{route}", json={"privateText": "Do not echo this"})
    assert response.status_code == 422
    assert "Do not echo this" not in response.text
    api = app.openapi()
    name = api["paths"][f"/internal/v1/{route}"]["post"]["responses"]["422"]["content"]["application/json"][
        "schema"
    ]["$ref"].split("/")[-1]
    validate_schema(api, name, response.json())


def test_clarification_is_only_for_author_and_does_not_confirm_content():
    payload = fixture("clarify-request.json")
    original = copy.deepcopy(payload)
    response = client.post("/internal/v1/clarify-items", json=payload).json()
    assert payload == original
    assert response["recipient"] == "AUTHOR"
    assert response["mode"] == "RULES"
    assert response["items"][0]["readiness"] == "NEEDS_DETAILS"
    assert [q["field"] for q in response["items"][0]["questions"]] == ["location"]
    assert response["items"][1] == {"proposalId": 2, "readiness": "READY_FOR_REVIEW", "questions": []}
    payload["items"][0]["location"] = "Этаж 3"
    after = client.post("/internal/v1/clarify-items", json=payload).json()
    assert all(item["readiness"] == "READY_FOR_REVIEW" for item in after["items"])
    assert "submissionStatus" not in after
    payload["recipient"] = "NEIGHBORS"
    assert client.post("/internal/v1/clarify-items", json=payload).status_code == 422


@pytest.mark.parametrize(
    "change", ["draft", "missing_item", "bool_limit", "self", "foreign_house", "shared_item", "duplicate"]
)
def test_similarity_rejects_unsafe_or_incomplete_context(change):
    payload = fixture("similar-request.json")
    if change == "draft":
        payload["query"]["submissionStatus"] = "DRAFT"
    elif change == "missing_item":
        payload["query"]["items"] = []
    elif change == "self":
        payload["candidates"] = [payload["query"]]
    elif change == "foreign_house":
        payload["candidates"][0]["houseId"] = "00000000-0000-0000-0000-000000000099"
    elif change == "shared_item":
        payload["candidates"][0]["items"][0]["itemId"] = payload["query"]["items"][0]["itemId"]
    elif change == "duplicate":
        payload["candidates"].append(payload["candidates"][0])
    else:
        payload["limit"] = True
    assert client.post("/internal/v1/similar-cases", json=payload).status_code == 422


def test_unindexed_candidates_return_an_explicit_analysis_failure():
    payload = fixture("similar-request.json")
    result = client.post("/internal/v1/similar-cases", json=payload).json()
    assert result["status"] == "NOT_ANALYZED"
    assert result["reason"] == "ANALYSIS_UNAVAILABLE"
    assert result["scope"] == "CHROMA"
    assert result["results"] == []


def test_synthetic_extraction_is_a_valid_fixture_but_not_the_stub_output():
    proposal = fixture("extract-response.synthetic-proposals.json")
    ExtractItemsResponse.model_validate(proposal)
    api = app.openapi()
    validate_schema(api, "ExtractItemsResponse", proposal)
    source = fixture("extract-request.json")["sourceText"]
    for item in proposal["proposals"]:
        evidence = item["evidence"]
        assert source[evidence["start"] : evidence["end"]] == evidence["quote"]
    proposal["mode"] = "STUB"
    with pytest.raises(ValidationError):
        ExtractItemsResponse.model_validate(proposal)


def test_confirmed_context_schema_is_current():
    schema = json.loads((ROOT / "confirmed-context.schema.json").read_text())
    expected = {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        **ConfirmedCaseContext.model_json_schema(),
    }
    assert schema == expected
    Draft202012Validator.check_schema(schema)
    Draft202012Validator(schema, format_checker=FormatChecker()).validate(fixture("confirmed-context.json"))


@pytest.mark.parametrize(
    "name,example",
    [
        ("UpdateDraftItems", "review-items-request.json"),
        ("ConfirmItems", "confirm-items-request.json"),
        ("AuthorCaseView", "review-items-response.json"),
        ("AuthorCaseView", "confirm-items-response.json"),
        ("Problem", "review-version-conflict.json"),
        ("MeView", "me-response.json"),
        ("MeView", "me-no-access.json"),
        ("CasePage", "case-list-response.json"),
        ("CasePage", "case-list-empty.json"),
        ("Problem", "unauthorized-401.json"),
        ("Problem", "forbidden-403.json"),
        ("Problem", "not-found-404.json"),
        ("Problem", "invalid-fields-422.json"),
        ("TargetCaseView", "case-view-target.json"),
        ("SaveReport", "report-create-request.json"),
        ("ConfirmReportLinks", "report-links-request.json"),
        ("CreateReportResponse", "report-create-response.json"),
        ("WorkReportView", "report-view.json"),
        ("RecordObservation", "observation-request.json"),
        ("DecideItem", "decision-withdraw-request.json"),
        ("DecideItem", "decision-archive-request.json"),
    ],
)
def test_planned_java_review_examples_match_schema(name, example):
    api = json.loads((ROOT / "author-review.openapi.json").read_text())
    for schema in api["components"]["schemas"].values():
        Draft202012Validator.check_schema(schema)
    validate_schema(api, name, fixture(example))

def test_implemented_operations_never_answer_with_a_target_schema():
    """The S-04 review found an IMPLEMENTED route answering with a schema Java does not return."""
    api = json.loads((ROOT / "author-review.openapi.json").read_text())
    schemas = api["components"]["schemas"]
    target_only = {
        name for name, schema in schemas.items()
        if str(schema.get("description", "")).startswith("TARGET")
    }
    assert target_only, "no schema is marked TARGET any more, so this test guards nothing"

    def direct_refs(node):
        if isinstance(node, dict):
            if "$ref" in node:
                yield node["$ref"].rsplit("/", 1)[-1]
            for value in node.values():
                yield from direct_refs(value)
        elif isinstance(node, list):
            for value in node:
                yield from direct_refs(value)

    def reachable(node):
        """A TARGET schema nested inside a returned one is just as wrong as a direct answer."""
        found, queue = set(), list(direct_refs(node))
        while queue:
            name = queue.pop()
            if name in found or name not in schemas:
                continue
            found.add(name)
            queue.extend(direct_refs(schemas[name]))
        return found

    for path, methods in api["paths"].items():
        for method, operation in methods.items():
            status = operation["x-implementation-status"]
            assert status in {"IMPLEMENTED", "TARGET", "SCHEMA_ONLY"}, (path, method, status)
            if status != "IMPLEMENTED":
                continue
            leaked = reachable(operation["responses"]) & target_only
            assert not leaked, f"{method.upper()} {path} answers with {sorted(leaked)}"

def test_author_review_does_not_confuse_confirmed_content_with_completed_work():
    before, after = fixture("review-items-response.json"), fixture("confirm-items-response.json")
    assert before["submissionStatus"] == "DRAFT" and after["submissionStatus"] == "CONFIRMED"
    assert after["caseVersion"] == before["caseVersion"] + 1
    assert [item["itemId"] for item in before["items"]] == [item["itemId"] for item in after["items"]]
    assert after["progress"] == before["progress"]
    assert after["progress"]["allConfirmed"] is False
    assert all(item["workRound"] == 0 and item["observation"] == "NOT_RECEIVED" for item in after["items"])


def test_chunked_oversized_body_is_rejected_before_json_parsing():
    def chunks():
        yield b'{"privateText":"'
        yield b"x" * MAX_REQUEST_BYTES
        yield b'"}'

    response = client.post(
        "/internal/v1/similar-cases", content=chunks(), headers={"Content-Type": "application/json"}
    )
    assert response.status_code == 413
    assert response.json() == {"detail": "REQUEST_TOO_LARGE"}
    validate_schema(app.openapi(), "BodySizeError", response.json())
