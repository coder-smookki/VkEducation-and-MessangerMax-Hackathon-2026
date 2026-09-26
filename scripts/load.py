"""Measured load on the core API (S-07 / testing.md): N signed sessions, 80% reads / 20% writes.

    python3 scripts/load.py                      # 10 sessions for 10 minutes, as testing.md asks
    python3 scripts/load.py --duration 60        # a one-minute rehearsal

Each session is a real resident: initData signed with the bot token, the masked CSRF token, its own
cookie jar. The script creates one synthetic house and its own cases and never touches anything else,
but it writes to the database it is pointed at: run it on a local or test stand, never on live data.

Reported: latency percentiles overall and per operation, 5xx share, intended 409s apart from errors,
database size, and the machine it ran on. The targets (p95 < 1 s, 5xx < 1 %) decide the exit code.
"""

import argparse
import hashlib
import hmac
import json
import os
import platform
import random
import statistics
import subprocess
import threading
import time
import urllib.error
import urllib.request
from collections import defaultdict
from http.cookiejar import CookieJar
from pathlib import Path
from urllib.parse import quote
from uuid import uuid4

parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
parser.add_argument("--api-url", default="http://127.0.0.1:8080")
parser.add_argument("--bot-token", default=None)
parser.add_argument("--sessions", type=int, default=10)
parser.add_argument("--duration", type=int, default=600, help="seconds of measured load")
parser.add_argument("--write-share", type=float, default=0.2)
parser.add_argument("--think-ms", type=int, default=200, help="pause between one session's requests")
parser.add_argument("--seed-cases", type=int, default=3, help="confirmed cases per session before measuring")
parser.add_argument("--db-service", default="db")
parser.add_argument("--db-user", default="koren")
parser.add_argument("--db-name", default="koren")
args = parser.parse_args()

ROOT = Path(__file__).resolve().parents[1]
P95_TARGET_SECONDS = 1.0
SERVER_ERROR_TARGET = 0.01


def bot_token() -> str:
    if args.bot_token:
        return args.bot_token
    env = ROOT / ".env"
    if env.exists():
        for line in env.read_text(encoding="utf-8").splitlines():
            if line.startswith("MAX_BOT_TOKEN=") and line.split("=", 1)[1].strip():
                return line.split("=", 1)[1].strip()
    raise SystemExit("MAX_BOT_TOKEN not found; pass --bot-token or set it in .env")


def sign_init_data(token: str, user_id: int) -> str:
    """Same algorithm as scripts/qa_e03.py and the Java validator: sorted, percent-decoded values."""
    user = json.dumps(
        {
            "id": user_id,
            "first_name": "Нагрузка",
            "last_name": str(user_id),
            "username": None,
            "language_code": "ru",
        },
        ensure_ascii=False,
        separators=(",", ":"),
    )
    values = {"auth_date": str(int(time.time())), "query_id": "load-" + uuid4().hex[:16], "user": user}
    check = "\n".join(f"{key}={values[key]}" for key in sorted(values))
    secret = hmac.new(b"WebAppData", token.encode("utf-8"), hashlib.sha256).digest()
    digest = hmac.new(secret, check.encode("utf-8"), hashlib.sha256).hexdigest()
    return "&".join(f"{key}={quote(value, safe='')}" for key, value in values.items()) + "&hash=" + digest


def psql(sql: str) -> str:
    result = subprocess.run(
        ["docker", "compose", "exec", "-T", args.db_service, "psql", "-U", args.db_user, "-d", args.db_name]
        + ["-v", "ON_ERROR_STOP=1", "-At", "-c", sql],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise SystemExit("psql failed:\n" + result.stderr.strip())
    return result.stdout.strip()


class Session:
    """One resident: cookie jar, session, masked CSRF token, and the cases it created."""

    def __init__(self, max_user_id: int):
        self.max_user_id = max_user_id
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(CookieJar()))
        self.csrf_header = None
        self.csrf_token = None
        self.user_id = None
        self.confirmed = []
        self.draft = None

    def send(self, method, path, body=None):
        """Returns (status, json or None, seconds). Never raises on an HTTP status: the caller counts it."""
        data = None if body is None else json.dumps(body).encode("utf-8")
        request = urllib.request.Request(args.api_url + path, data=data, method=method)
        if data is not None:
            request.add_header("Content-Type", "application/json")
        if method != "GET":
            request.add_header("Idempotency-Key", str(uuid4()))
            if self.csrf_token:
                request.add_header(self.csrf_header, self.csrf_token)
        started = time.perf_counter()
        try:
            with self.opener.open(request, timeout=30) as response:
                text = response.read().decode("utf-8")
                status = response.status
        except urllib.error.HTTPError as error:
            text = error.read().decode("utf-8", "replace")
            status = error.code
        seconds = time.perf_counter() - started
        try:
            parsed = json.loads(text) if text else None
        except ValueError:
            parsed = None
        return status, parsed, seconds

    def must(self, method, path, body=None):
        status, parsed, _ = self.send(method, path, body)
        if status >= 300:
            raise SystemExit(f"setup: {method} {path} -> {status}: {parsed}")
        return parsed

    def login(self, token):
        csrf = self.must("GET", "/api/v1/csrf")
        self.csrf_header, self.csrf_token = csrf["headerName"], csrf["token"]
        self.user_id = self.must(
            "POST", "/api/v1/auth/max", {"initData": sign_init_data(token, self.max_user_id)}
        )["userId"]


def new_case_body(house):
    return {
        "houseId": house,
        "sourceText": f"Нагрузка {uuid4().hex[:8]}: темно на этаже и не закрывается дверь.",
    }


def items_body(version):
    return {
        "expectedCaseVersion": version,
        "items": [
            {"itemId": None, "category": "LIGHTING", "location": "Этаж 3", "symptom": "Не горит свет"},
            {
                "itemId": None,
                "category": "ENTRANCE_DOOR",
                "location": "Вход",
                "symptom": "Дверь не закрывается",
            },
        ],
    }


def seed(session, house):
    for _ in range(args.seed_cases):
        case = session.must("POST", "/api/v1/cases", new_case_body(house))
        case = session.must("PATCH", f"/api/v1/cases/{case['caseId']}/items", items_body(case["caseVersion"]))
        case = session.must(
            "POST",
            f"/api/v1/cases/{case['caseId']}/confirm",
            {"expectedCaseVersion": case["caseVersion"], "sourceRevision": case["sourceRevision"]},
        )
        session.confirmed.append(case["caseId"])


def read_operation(session, house):
    choice = random.random()
    if choice < 0.25:
        return "GET /me", session.send("GET", "/api/v1/me")
    if choice < 0.5:
        return "GET /cases (list)", session.send("GET", f"/api/v1/cases?houseId={house}&limit=20")
    case_id = random.choice(session.confirmed)
    if choice < 0.8:
        return "GET /cases/{id}", session.send("GET", f"/api/v1/cases/{case_id}")
    return "GET /cases/{id}/history", session.send("GET", f"/api/v1/cases/{case_id}/history?limit=20")


def write_operation(session, house):
    """Alternates: open a draft, then give it items. A 409 here is a real version race, counted apart."""
    if session.draft is None:
        result = session.send("POST", "/api/v1/cases", new_case_body(house))
        if result[0] == 201 and result[1]:
            session.draft = (result[1]["caseId"], result[1]["caseVersion"])
        return "POST /cases", result
    case_id, version = session.draft
    session.draft = None
    return "PATCH /cases/{id}/items", session.send(
        "PATCH", f"/api/v1/cases/{case_id}/items", items_body(version)
    )


samples = defaultdict(list)
statuses = defaultdict(lambda: defaultdict(int))
transport_errors = defaultdict(int)
lock = threading.Lock()


def run(session, house, deadline):
    while time.monotonic() < deadline:
        operation = write_operation if random.random() < args.write_share else read_operation
        try:
            name, (status, _, seconds) = operation(session, house)
        except (urllib.error.URLError, TimeoutError, ConnectionError) as failure:
            with lock:
                transport_errors[type(failure).__name__] += 1
            continue
        with lock:
            samples[name].append(seconds)
            statuses[name][status] += 1
        time.sleep(args.think_ms / 1000)


def percentile(values, share):
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, round(share * (len(ordered) - 1)))]


def main():
    token = bot_token()
    house = str(uuid4())
    base = random.randint(700_000_000, 799_000_000)
    sessions = [Session(base + index) for index in range(args.sessions)]
    print(f"Setup: {args.sessions} sessions, house {house}, {args.seed_cases} confirmed cases each")
    for session in sessions:
        session.login(token)
    psql(f"INSERT INTO house(id, address) VALUES ('{house}', 'Нагрузка {house[:8]}')")
    values = ", ".join(f"('{s.user_id}', '{house}', 'RESIDENT')" for s in sessions)
    psql(f"INSERT INTO membership(user_id, house_id, role) VALUES {values}")
    for session in sessions:
        seed(session, house)

    print(f"Measuring {args.duration}s, {int(args.write_share * 100)}% writes, think {args.think_ms} ms ...")
    started = time.monotonic()
    deadline = started + args.duration
    threads = [threading.Thread(target=run, args=(s, house, deadline)) for s in sessions]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    elapsed = time.monotonic() - started

    everything = [seconds for values in samples.values() for seconds in values]
    total = len(everything)
    if total == 0:
        raise SystemExit("No request completed; is the backend up at " + args.api_url + "?")
    all_statuses = defaultdict(int)
    for per in statuses.values():
        for status, count in per.items():
            all_statuses[status] += count
    server_errors = sum(count for status, count in all_statuses.items() if status >= 500)
    conflicts = all_statuses.get(409, 0)
    other_client = sum(
        count for status, count in all_statuses.items() if 400 <= status < 500 and status != 409
    )

    print(f"\n{'operation':28} {'n':>6} {'p50 ms':>8} {'p95 ms':>8} {'p99 ms':>8}  statuses")
    for name in sorted(samples):
        values = samples[name]
        codes = " ".join(f"{code}:{count}" for code, count in sorted(statuses[name].items()))
        print(
            f"{name:28} {len(values):6} {percentile(values, 0.5) * 1000:8.1f} "
            f"{percentile(values, 0.95) * 1000:8.1f} {percentile(values, 0.99) * 1000:8.1f}  {codes}"
        )
    p95 = percentile(everything, 0.95)
    print(
        f"{'ALL':28} {total:6} {percentile(everything, 0.5) * 1000:8.1f} {p95 * 1000:8.1f} "
        f"{percentile(everything, 0.99) * 1000:8.1f}  mean {statistics.fmean(everything) * 1000:.1f}"
    )
    print(f"\nthroughput {total / elapsed:.1f} req/s over {elapsed:.0f}s, {args.sessions} sessions")
    print(f"5xx {server_errors} ({server_errors / total:.2%}); 409 {conflicts} (intended races, not errors);")
    print(f"other 4xx {other_client}; transport errors {dict(transport_errors) or 0}")
    print(
        f"database {psql('SELECT pg_size_pretty(pg_database_size(current_database()))')}, "
        f"cases {psql('SELECT count(*) FROM service_case')}, audit {psql('SELECT count(*) FROM audit_event')}"
    )
    print(f"machine {platform.platform()}, {os.cpu_count()} CPU, Python {platform.python_version()}")
    print("Record next to these numbers: how Java and PostgreSQL were run, and the Python analysis mode.")

    passed = p95 < P95_TARGET_SECONDS and server_errors / total < SERVER_ERROR_TARGET and other_client == 0
    print(
        "\nRESULT " + ("PASS" if passed else "FAIL") + f": p95 {p95 * 1000:.0f} ms (target < 1000), "
        f"5xx {server_errors / total:.2%} (target < 1%), unexpected 4xx {other_client}"
    )
    raise SystemExit(0 if passed else 1)


main()
