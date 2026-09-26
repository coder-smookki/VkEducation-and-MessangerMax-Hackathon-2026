"""Smoke of a deployed stand (S-09, S-10, QA-R09): is the right version up, and is nothing open that should not be.

    python3 scripts/stand_smoke.py                                   # https://hackathon.lumnex.ru, SHA of HEAD
    python3 scripts/stand_smoke.py --expect-sha <sha>                # the SHA recorded in docs/release.md
    python3 scripts/stand_smoke.py --login-max-user-id 700000001     # also sign in once with MAX_BOT_TOKEN
    python3 scripts/stand_smoke.py --base-url http://127.0.0.1:8080 --local   # rehearsal on a laptop

Read-only apart from the optional sign-in, which creates or reuses one synthetic account. Nothing it prints
is secret: no token, cookie or initData. Copy the RESULT line and the SHA into docs/operations-log.md.
"""

import argparse
import hashlib
import hmac
import json
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from http.cookiejar import CookieJar
from pathlib import Path
from urllib.parse import quote, urlparse
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]
# Internal services that must never answer from outside: PostgreSQL, Python, Kafka, the Python test port.
PRIVATE_PORTS = (5432, 8000, 9092, 18081)
CLOSED_PATHS = (
    "/actuator",
    "/actuator/env",
    "/actuator/metrics",
    "/actuator/configprops",
    "/api/v1/demo/case",
)

parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
parser.add_argument("--base-url", default="https://hackathon.lumnex.ru")
parser.add_argument("--expect-sha", default=None, help="default: git rev-parse HEAD of this checkout")
parser.add_argument(
    "--login-max-user-id", type=int, default=None, help="sign in once as this synthetic MAX user"
)
parser.add_argument("--bot-token", default=None, help="default: MAX_BOT_TOKEN from .env")
parser.add_argument("--local", action="store_true", help="allow http and skip the private-port check")
args = parser.parse_args()
BASE = args.base_url.rstrip("/")

results = []


def check(label, ok, detail=""):
    results.append(ok)
    print(("  PASS  " if ok else "  FAIL  ") + label + (f"   [{detail}]" if detail else ""))


def fetch(path, method="GET", body=None, headers=None, opener=None):
    """(status, headers, text); never raises on an HTTP status."""
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(BASE + path, data=data, method=method, headers=headers or {})
    if data is not None:
        request.add_header("Content-Type", "application/json")
    request.add_header("Accept", "application/json")
    try:
        with (opener or urllib.request.build_opener()).open(request, timeout=15) as response:
            return response.status, response.headers, response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        return error.code, error.headers, error.read().decode("utf-8", "replace")
    except (urllib.error.URLError, TimeoutError, ConnectionError) as failure:
        return None, {}, type(failure).__name__


def parse(text):
    try:
        return json.loads(text)
    except ValueError:
        return None


def expected_sha():
    if args.expect_sha:
        return args.expect_sha
    result = subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True, text=True, check=False
    )
    return result.stdout.strip() or None


def bot_token():
    if args.bot_token:
        return args.bot_token
    env = ROOT / ".env"
    if env.exists():
        for line in env.read_text(encoding="utf-8").splitlines():
            if line.startswith("MAX_BOT_TOKEN=") and line.split("=", 1)[1].strip():
                return line.split("=", 1)[1].strip()
    raise SystemExit("--login-max-user-id needs MAX_BOT_TOKEN in .env or --bot-token")


def init_data(token, user_id):
    user = json.dumps(
        {"id": user_id, "first_name": "Smoke", "last_name": "Check", "username": None, "language_code": "ru"},
        ensure_ascii=False,
        separators=(",", ":"),
    )
    values = {"auth_date": str(int(time.time())), "query_id": "smoke-" + uuid4().hex[:16], "user": user}
    check_string = "\n".join(f"{key}={values[key]}" for key in sorted(values))
    secret = hmac.new(b"WebAppData", token.encode(), hashlib.sha256).digest()
    digest = hmac.new(secret, check_string.encode(), hashlib.sha256).hexdigest()
    return "&".join(f"{key}={quote(value, safe='')}" for key, value in values.items()) + "&hash=" + digest


def surface():
    scheme = urlparse(BASE).scheme
    if args.local and scheme != "https":
        print(f"  SKIP  HTTPS (--local, {scheme})")
    else:
        check("served over HTTPS", scheme == "https", scheme)

    status, headers, text = fetch("/actuator/health")
    health = parse(text) or {}
    check("health is UP", status == 200 and health.get("status") == "UP", f"{status} {text[:60]}")
    check("health shows no internals", "components" not in health and "details" not in health)
    check("every answer carries X-Request-Id", bool(headers and headers.get("X-Request-Id")))

    sha = expected_sha()
    status, _, text = fetch("/actuator/info")
    running = ((parse(text) or {}).get("app") or {}).get("sha")
    check(
        "the stand runs the expected commit",
        bool(sha) and running == sha,
        f"running {running}, expected {sha}",
    )

    for path in CLOSED_PATHS:
        status, _, _ = fetch(path)
        check(f"{path} is closed", status in (401, 403, 404), str(status))

    status, headers, text = fetch("/api/v1/no-such-route")
    content_type = headers.get("Content-Type", "") if headers else ""
    check(
        "an API error is problem+json, not the SPA page",
        content_type.startswith("application/problem+json") and "<html" not in text.lower(),
        f"{status} {content_type}",
    )

    status, _, _ = fetch("/max/webhook")
    check("the webhook does not answer a plain GET", status != 200, str(status))

    if args.local:
        print("  SKIP  private ports (--local)")
        return
    host = urlparse(BASE).hostname
    for port in PRIVATE_PORTS:
        try:
            with socket.create_connection((host, port), timeout=3):
                reachable = True
        except OSError:
            reachable = False
        check(f"port {port} is not reachable from outside", not reachable)


def login():
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CookieJar()))
    status, _, text = fetch("/api/v1/csrf", opener=opener)
    csrf = parse(text) or {}
    check("CSRF token is issued", status == 200 and "token" in csrf, str(status))
    if status != 200:
        return
    status, headers, _ = fetch(
        "/api/v1/auth/max",
        method="POST",
        body={"initData": init_data(bot_token(), args.login_max_user_id)},
        headers={csrf["headerName"]: csrf["token"]},
        opener=opener,
    )
    check("sign-in with a signed initData works", status == 200, str(status))
    cookies = headers.get_all("Set-Cookie") if headers else []
    session = next((c for c in cookies or [] if c.upper().startswith("JSESSIONID")), "")
    check("the session cookie is HttpOnly", "httponly" in session.lower())
    check("the session cookie is Secure", "secure" in session.lower() or args.local)
    status, _, _ = fetch("/api/v1/me", opener=opener)
    check("the session survives the next request", status == 200, str(status))


print(f"Stand smoke {BASE} at {time.strftime('%Y-%m-%d %H:%M:%S %Z')}")
surface()
if args.login_max_user_id is not None:
    login()
passed = all(results)
print(
    f"\nRESULT {'PASS' if passed else 'FAIL'}: {sum(results)} of {len(results)} checks, expected SHA {expected_sha()}"
)
sys.exit(0 if passed else 1)
