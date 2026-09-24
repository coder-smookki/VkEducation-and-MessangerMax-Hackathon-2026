"""Verify the real React proxy -> Java -> Python path on synthetic data only."""
import argparse
import json
import time
import urllib.error
import urllib.request
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--web-url", default="http://127.0.0.1:5173")
parser.add_argument("--api-url", default="http://127.0.0.1:8080")
parser.add_argument("--wait-seconds", type=int, default=60)
parser.add_argument("--expect-mode", choices=["STUB", "MANUAL"], default="STUB")
args = parser.parse_args()


def fetch(url):
    with urllib.request.urlopen(url, timeout=5) as response:
        return response.read().decode("utf-8")


deadline = time.monotonic() + args.wait_seconds
while True:
    try:
        assert json.loads(fetch(args.api_url + "/actuator/health"))["status"] == "UP"
        assert '<div id="root"></div>' in fetch(args.web_url)
        break
    except (urllib.error.URLError, ConnectionError, TimeoutError, AssertionError):
        if time.monotonic() >= deadline:
            raise SystemExit("Stack did not become ready; inspect docker compose ps/logs.")
        time.sleep(1)

root = Path(__file__).resolve().parents[1]
expected = json.loads((root / "contracts/examples/compare-response.json").read_text())
request = json.loads((root / "contracts/examples/compare-request.json").read_text())
for base in (args.api_url, args.web_url):
    case = json.loads(fetch(base + "/api/v1/demo/case"))
    assert case["synthetic"] is True
    assert case["caseId"] == request["caseId"]
    assert {item["detail"]["itemId"] for item in case["items"]} == {i["itemId"] for i in request["items"]}
    assert case["progress"] == {"total": 2, "confirmed": 0, "withdrawn": 0, "remaining": 2, "allConfirmed": False}
    result = json.loads(fetch(base + "/api/v1/demo/compare"))
    assert result["mode"] == args.expect_mode, result["mode"]
    if args.expect_mode == "STUB":
        assert result == expected, "Java/Python/example contract drift"
    else:
        assert result["caseId"] == case["caseId"]
        assert len(result["suggestions"]) == 2
        assert all(s["relation"] == "INSUFFICIENT_INFORMATION" and s["evidence"] is None
                   and s["reason"] == "ANALYSIS_UNAVAILABLE" for s in result["suggestions"])
        try:
            fetch(base + "/api/v1/cases")
            raise AssertionError("Business route must require a session")
        except urllib.error.HTTPError as error:
            assert error.code == 401, f"anonymous read must be 401 AUTH_REQUIRED, got {error.code}"
print(f"PASS: frontend, proxy, Java, {args.expect_mode} comparison, full item list, business routes require a session")
