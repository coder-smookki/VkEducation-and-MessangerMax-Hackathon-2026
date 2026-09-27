"""Full DATA-API scenario on a disposable real Java/PostgreSQL stand: 0/2 -> 1/2, the door untouched.

    python3 scripts/check_data_api.py              # build a throwaway stand, two fresh runs, tear down
    python3 scripts/check_data_api.py --runs 3     # more fresh runs

What it proves, through the public HTTP API only (the same nginx -> Java path MAX uses):
author creates a case with two items (light, door), confirms it; the dispatcher accepts it, sets a next
step for the door, saves a report whose scope is both items, links it to the light only; the author
answers RESTORED for the light. Progress must go 0/2 -> 1/2 with exact values, the door must keep its
version, round, status and next step. Then: an exact replay returns the stored answer and adds no event,
the same key with another body is 409 IDEMPOTENCY_KEY_REUSED, a resident of another house gets 404,
the dispatcher may not answer for the author (403), a stale caseVersion is 409, a second acceptance is
422, and the history holds exactly one event per command.

This is our own reproducible check, not the organizers' DATA-API runner and not a MAX client: sessions
are signed with a random test-only bot token of the throwaway stand. It never contacts a live stand.
Only synthetic data is created; the uniquely named Compose project and its volumes are removed in
finally. Secrets (token, initData, cookies, CSRF, invitation codes) are never printed or written.
Requires Docker Compose and Python 3.11+.
"""

import argparse
import hashlib
import hmac
import json
import os
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid
from datetime import UTC, datetime, timedelta
from http.cookiejar import CookieJar
from pathlib import Path
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[1]
PROJECT_PREFIX = "koren-dapi-check-"
API = "/api/v1"
# Spring adds "instance" (the request path) to a ProblemDetail; nothing else may appear.
PROBLEM_KEYS = {"type", "title", "status", "code", "requestId", "instance"}
# Refusals whose body lacked the requestId of the X-Request-Id header, reported once per run.
MISSING_BODY_REQUEST_ID = []
EVENTS = [
    "CASE_CREATED",
    "CASE_ITEMS_PATCHED",
    "CASE_CONFIRMED",
    "CASE_ACCEPTED",
    "ITEM_NEXT_ACTION_SET",
    "REPORT_SAVED",
    "REPORT_LINK_CONFIRMED",
    "OBSERVATION_RECORDED",
]
LIGHT = {"category": "LIGHTING", "location": "Подъезд 1, этаж 3", "symptom": "Не горит свет"}
DOOR = {"category": "ENTRANCE_DOOR", "location": "Подъезд 1, вход", "symptom": "Дверь не закрывается"}
NEXT_ACTION_TEXT = "Проверить доводчик двери и сообщить следующий результат"
REPORT_TEXT = "Лампу на третьем этаже заменили"

parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
parser.add_argument("--runs", type=int, default=2, help="fresh scenarios, each with its own runId and keys")
parser.add_argument(
    "--report", default=None, help="JSON protocol path (default: output/qa/data-api-<time>.json)"
)
args = parser.parse_args()


class CheckFailed(Exception):
    """A required expectation failed; the dependent chain of this run stops here."""


class Protocol:
    """Every expectation with expected/actual, printed at once and kept for the JSON protocol."""

    def __init__(self):
        self.rows = []

    def check(self, run, check_id, ok, expected, actual):
        self.rows.append(
            {
                "run": run,
                "check": check_id,
                "status": "PASS" if ok else "FAIL",
                "expected": expected,
                "actual": actual,
            }
        )
        print(
            f"{'PASS' if ok else 'FAIL'}  {run:<6} {check_id:<44} {actual if ok else f'{actual} (expected {expected})'}"
        )
        return ok

    def require(self, run, check_id, ok, expected, actual):
        if not self.check(run, check_id, ok, expected, actual):
            raise CheckFailed(check_id)

    @property
    def failed(self):
        return [row for row in self.rows if row["status"] == "FAIL"]


class Answer:
    def __init__(self, status, body, request_id, content_type):
        self.status = status
        self.json = body
        self.request_id = request_id
        self.content_type = content_type or ""


class Client:
    """One role: its own cookie jar and its own CSRF token, never shared with another role."""

    def __init__(self, base_url, name):
        self.base_url = base_url
        self.name = name
        self.jar = CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))
        self.csrf = None

    def call(self, method, path, body=None, key=None):
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(self.base_url + path, data=data, method=method)
        request.add_header("Accept", "application/json")
        if data is not None:
            request.add_header("Content-Type", "application/json")
        if key is not None:
            request.add_header("Idempotency-Key", key)
        if method != "GET" and self.csrf:
            request.add_header(self.csrf[0], self.csrf[1])
        try:
            with self.opener.open(request, timeout=30) as response:
                status, raw, headers = response.status, response.read(), response.headers
        except urllib.error.HTTPError as error:
            status, raw, headers = error.code, error.read(), error.headers
        text = raw.decode("utf-8", "replace")
        try:
            parsed = json.loads(text) if text else None
        except ValueError:
            parsed = None
        return Answer(status, parsed, headers.get("X-Request-Id"), headers.get("Content-Type"))

    def refresh_csrf(self):
        answer = self.call("GET", API + "/csrf")
        if answer.status != 200 or not answer.json:
            raise CheckFailed(f"{self.name}: GET /csrf -> {answer.status}")
        self.csrf = (answer.json["headerName"], answer.json["token"])

    def login(self, bot_token, max_user_id):
        self.refresh_csrf()
        answer = self.call(
            "POST", API + "/auth/max", {"initData": sign_init_data(bot_token, max_user_id, self.name)}
        )
        if answer.status != 200:
            raise CheckFailed(f"{self.name}: login -> {answer.status} {code(answer)}")
        # The session id changes on login; the token is taken again in the new session.
        self.refresh_csrf()
        return answer.json

    def cookie_values(self):
        return [cookie.value for cookie in self.jar if cookie.value]


def sign_init_data(token, user_id, name):
    """The MAX WebApp algorithm the Java validator checks; used only with the throwaway stand's token."""
    user = json.dumps(
        {"id": user_id, "first_name": "Проверка", "last_name": name, "language_code": "ru"},
        ensure_ascii=False,
        separators=(",", ":"),
    )
    values = {"auth_date": str(int(time.time())), "query_id": "dapi-" + uuid.uuid4().hex[:16], "user": user}
    check = "\n".join(f"{key}={values[key]}" for key in sorted(values))
    secret = hmac.new(b"WebAppData", token.encode("utf-8"), hashlib.sha256).digest()
    digest = hmac.new(secret, check.encode("utf-8"), hashlib.sha256).hexdigest()
    return "&".join(f"{key}={quote(value, safe='')}" for key, value in values.items()) + "&hash=" + digest


def code(answer):
    return answer.json.get("code") if isinstance(answer.json, dict) else None


def is_int(value):
    return isinstance(value, int) and not isinstance(value, bool)


def parse_instant(value):
    return datetime.fromisoformat(value) if isinstance(value, str) else None


def item_of(case, item_id):
    found = [item for item in case["items"] if item["itemId"] == item_id]
    if len(found) != 1:
        raise CheckFailed(f"item {item_id} not found exactly once")
    return found[0]


def progress(case):
    p = case["progress"]
    return (p["total"], p["confirmed"], p["withdrawn"], p["remaining"], p["allConfirmed"])


ZERO_OF_TWO = (2, 0, 0, 2, False)
ONE_OF_TWO = (2, 1, 0, 1, False)


def types_ok(case):
    """Versions and rounds stay JSON integers, flags booleans, ids strings: a quoted "${x}" would fail."""
    top = (
        is_int(case["caseVersion"])
        and is_int(case["sourceRevision"])
        and isinstance(case["caseId"], str)
        and isinstance(case["progress"]["allConfirmed"], bool)
    )
    counts = all(is_int(case["progress"][name]) for name in ("total", "confirmed", "withdrawn", "remaining"))
    items = all(
        isinstance(item["itemId"], str)
        and is_int(item["itemVersion"])
        and is_int(item["contentVersion"])
        and is_int(item["workRound"])
        for item in case["items"]
    )
    return top and counts and items


class Stand:
    """A uniquely named release-configuration stand on a random loopback port, removed with its volumes."""

    def __init__(self):
        self.project = PROJECT_PREFIX + secrets.token_hex(4)
        self.bot_token = secrets.token_urlsafe(32)
        self.sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        self.dirty = bool(
            subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip()
        )
        self.directory = tempfile.TemporaryDirectory(prefix=self.project)
        self.env = {
            key: value
            for key, value in os.environ.items()
            if not key.startswith("COMPOSE_")
            and key
            not in {
                "DB_PASSWORD",
                "MAX_BOT_TOKEN",
                "MAX_WEBHOOK_SECRET",
                "MAX_WORKERS_ENABLED",
                "RELEASE_SHA",
                "ANALYSIS_ENABLED",
                "TRAINING_MODE",
                "CASE_NUMBER_PREFIX",
                "SESSION_COOKIE_SECURE",
                "SESSION_COOKIE_SAMESITE",
                "WEB_PORT",
                "SITE_ADDRESS",
            }
        }
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            self.port = listener.getsockname()[1]
        env_file = Path(self.directory.name) / "stand.env"
        env_file.write_text(
            f"DB_PASSWORD={secrets.token_urlsafe(32)}\nMAX_BOT_TOKEN={self.bot_token}\n"
            f"MAX_WEBHOOK_SECRET={secrets.token_urlsafe(32)}\nMAX_WORKERS_ENABLED=false\n"
            f"RELEASE_SHA={self.sha}\nTRAINING_MODE=true\nCASE_NUMBER_PREFIX=ДЕМО-\n"
            f"SESSION_COOKIE_SECURE=false\nSESSION_COOKIE_SAMESITE=Lax\nWEB_PORT={self.port}\n",
            encoding="utf-8",
        )
        env_file.chmod(0o600)
        self.compose = ["docker", "compose", "--env-file", str(env_file), "-p", self.project]
        self.compose += ["-f", "compose.release.yaml"]
        self.url = f"http://127.0.0.1:{self.port}"

    def up(self):
        print(f"Throwaway stand {self.project} on 127.0.0.1:{self.port}, source {self.sha[:12]}", flush=True)
        subprocess.run(
            self.compose + ["up", "--build", "--wait", "--wait-timeout", "240"],
            cwd=ROOT,
            env=self.env,
            check=True,
        )
        for _ in range(90):
            try:
                with urllib.request.urlopen(self.url + API + "/csrf", timeout=3) as response:
                    if response.status == 200:
                        return
            except (urllib.error.URLError, TimeoutError, ConnectionError):
                time.sleep(1)
        raise SystemExit("Backend did not become ready; no PASS claimed")

    def psql(self, sql):
        # Only this project's own database, and only values this script generated (UUIDs, hex hashes).
        assert self.project.startswith(PROJECT_PREFIX)
        result = subprocess.run(
            self.compose
            + ["exec", "-T", "db", "psql", "-U", "koren", "-d", "koren", "-v", "ON_ERROR_STOP=1"]
            + ["-At", "-c", sql],
            cwd=ROOT,
            env=self.env,
            capture_output=True,
            text=True,
            check=False,
        )
        if result.returncode != 0:
            raise SystemExit("psql failed on the throwaway stand:\n" + result.stderr.strip())
        return result.stdout.strip()

    def logs(self):
        result = subprocess.run(
            self.compose + ["logs", "--no-color"],
            cwd=ROOT,
            env=self.env,
            capture_output=True,
            text=True,
            check=False,
        )
        return result.stdout + result.stderr

    def down(self):
        assert self.project.startswith(PROJECT_PREFIX)
        subprocess.run(
            self.compose + ["down", "--volumes", "--remove-orphans"], cwd=ROOT, env=self.env, check=False
        )
        self.directory.cleanup()


def expect_problem(protocol, run, check_id, answer, status, problem_code):
    """A refusal is problem+json with the stable code, the request id of the header, and nothing else."""
    body = answer.json if isinstance(answer.json, dict) else {}
    ok = (
        answer.status == status
        and answer.content_type.startswith("application/problem+json")
        and body.get("code") == problem_code
        and answer.request_id is not None
        and set(body) <= PROBLEM_KEYS
    )
    protocol.require(
        run,
        check_id,
        ok,
        f"{status} {problem_code}, X-Request-Id, no foreign fields",
        f"{answer.status} {code(answer)} extra={sorted(set(body) - PROBLEM_KEYS)}",
    )
    # docs/api.md promises the same id in the body; checked apart so the scenario itself goes on.
    if body.get("requestId") != answer.request_id:
        MISSING_BODY_REQUEST_ID.append(f"{run}:{check_id}")


def prepare_roles(stand, protocol):
    """Two houses, three accounts. Residents join through a real one-time invitation and /memberships/claim;
    the dispatcher is set by the operator in SQL, as seed_stand.py does on a stand."""
    base = 900_000_000 + secrets.randbelow(90_000_000)
    clients = {name: Client(stand.url, name) for name in ("author", "dispatcher", "stranger")}
    me = {}
    for offset, (name, client) in enumerate(clients.items()):
        me[name] = client.login(stand.bot_token, base + offset)
    house_a, house_b = str(uuid.uuid4()), str(uuid.uuid4())
    stand.psql(
        f"INSERT INTO house(id, address) VALUES ('{house_a}', 'Учебный дом А DATA-API (тестовые данные)'),"
        f" ('{house_b}', 'Учебный дом Б DATA-API (тестовые данные)');"
    )
    codes = {}
    for name, house in (("author", house_a), ("stranger", house_b)):
        invitation = secrets.token_urlsafe(18)
        codes[name] = invitation
        digest = hashlib.sha256(invitation.strip().encode("utf-8")).hexdigest()
        stand.psql(
            "INSERT INTO house_invitation(id, house_id, code_hash, expires_at)"
            f" VALUES ('{uuid.uuid4()}', '{house}', '{digest}', now() + interval '1 hour');"
        )
        claimed = clients[name].call("POST", API + "/memberships/claim", {"invitationCode": invitation})
        protocol.require("setup", f"claim-invitation-{name}", claimed.status == 200, 200, claimed.status)
    dispatcher_id = me["dispatcher"]["userId"]
    uuid.UUID(dispatcher_id)
    stand.psql(
        f"INSERT INTO membership(user_id, house_id, role) VALUES ('{dispatcher_id}', '{house_a}', 'DISPATCHER');"
    )

    roles = {}
    for name, client in clients.items():
        answer = client.call("GET", API + "/me")
        protocol.require("setup", f"me-{name}", answer.status == 200, 200, answer.status)
        roles[name] = {
            "userId": answer.json["userId"],
            "houses": {m["houseId"]: m["role"] for m in answer.json["memberships"]},
        }
    matrix = {
        "author": {house_a: "RESIDENT"},
        "dispatcher": {house_a: "DISPATCHER"},
        "stranger": {house_b: "RESIDENT"},
    }
    for name, expected in matrix.items():
        protocol.require(
            "setup",
            f"role-matrix-{name}",
            roles[name]["houses"] == expected,
            f"exactly {sorted(expected.values())} in its own house",
            sorted(roles[name]["houses"].values()),
        )
    ids = {roles[name]["userId"] for name in roles}
    protocol.require("setup", "three-distinct-users", len(ids) == 3, 3, len(ids))
    secrets_seen = list(codes.values())
    return clients, roles, house_a, secrets_seen


def public_checks(stand, protocol):
    anonymous = Client(stand.url, "public")
    csrf = anonymous.call("GET", API + "/csrf")
    protocol.require(
        "public",
        "csrf-token",
        csrf.status == 200 and bool(csrf.json.get("headerName")) and bool(csrf.json.get("token")),
        "200 headerName+token",
        csrf.status,
    )
    for path, check_id in ((API + "/me", "me-anonymous"), (API + "/cases", "cases-anonymous")):
        expect_problem(protocol, "public", check_id, anonymous.call("GET", path), 401, "AUTH_REQUIRED")
    info = anonymous.call("GET", "/actuator/info")
    sha = (((info.json or {}).get("app") or {}).get("sha")) if info.status == 200 else None
    protocol.check("public", "stand-sha", sha == stand.sha, stand.sha[:12], (sha or "none")[:12])


def history_of(client, case_id):
    events, cursor = [], None
    for _ in range(20):
        path = f"{API}/cases/{case_id}/history?limit=50" + (
            f"&cursor={quote(cursor, safe='')}" if cursor else ""
        )
        answer = client.call("GET", path)
        if answer.status != 200:
            raise CheckFailed(f"history -> {answer.status}")
        events += answer.json["items"]
        cursor = answer.json["nextCursor"]
        if not cursor:
            return events
    raise CheckFailed("history did not end within 20 pages")


def queue_contains(client, house, case_id):
    cursor = None
    for _ in range(20):
        path = f"{API}/cases?houseId={house}&queueState=NEEDS_ACCEPTANCE&limit=50"
        path += f"&cursor={quote(cursor, safe='')}" if cursor else ""
        answer = client.call("GET", path)
        if answer.status != 200:
            raise CheckFailed(f"queue -> {answer.status}")
        if any(case["caseId"] == case_id for case in answer.json["items"]):
            return True
        cursor = answer.json["nextCursor"]
        if not cursor:
            return False
    return False


def scenario(run_no, clients, roles, house_a, protocol):  # pylint: disable=too-many-locals,too-many-statements
    run = f"run{run_no}"
    run_id = uuid.uuid4().hex[:12]
    author, dispatcher, stranger = clients["author"], clients["dispatcher"], clients["stranger"]
    author_id, dispatcher_id = roles["author"]["userId"], roles["dispatcher"]["userId"]

    def key(operation):
        return f"data-api-{run_id}-{operation}"

    source = f"Проверка DATA-API {run_id}: на третьем этаже не горит свет, входная дверь не закрывается."
    created = author.call("POST", API + "/cases", {"houseId": house_a, "sourceText": source}, key("create"))
    c = created.json or {}
    protocol.require(
        run,
        "create-case",
        created.status == 201
        and c.get("submissionStatus") == "DRAFT"
        and c.get("houseId") == house_a
        and c.get("authorId") == author_id
        and c.get("sourceText") == source,
        "201 DRAFT, house A, author, same text",
        f"{created.status} {c.get('submissionStatus')}",
    )
    case_id = c["caseId"]

    patched = author.call(
        "PATCH",
        f"{API}/cases/{case_id}/items",
        {
            "expectedCaseVersion": c["caseVersion"],
            "items": [dict(LIGHT, itemId=None), dict(DOOR, itemId=None)],
        },
        key("items"),
    )
    c = patched.json or {}
    shapes = [(i["category"], i["location"], i["symptom"]) for i in c.get("items", [])]
    expected_shapes = [tuple(LIGHT.values()), tuple(DOOR.values())]
    protocol.require(
        run,
        "patch-items",
        patched.status == 200 and shapes == expected_shapes,
        "200, exactly light then door",
        f"{patched.status} {[s[0] for s in shapes]}",
    )
    light_id, door_id = c["items"][0]["itemId"], c["items"][1]["itemId"]

    confirmed = author.call(
        "POST",
        f"{API}/cases/{case_id}/confirm",
        {"expectedCaseVersion": c["caseVersion"], "sourceRevision": c["sourceRevision"]},
        key("confirm"),
    )
    c = confirmed.json or {}
    protocol.require(
        run,
        "confirm-case",
        confirmed.status == 200
        and c.get("submissionStatus") == "CONFIRMED"
        and bool((c.get("receipt") or {}).get("confirmedAt"))
        and progress(c) == ZERO_OF_TWO
        and types_ok(c),
        "200 CONFIRMED, confirmedAt, 0/2, JSON types",
        f"{confirmed.status} {c.get('submissionStatus')} {progress(c) if c else None}",
    )

    read = author.call("GET", f"{API}/cases/{case_id}")
    r = read.json or {}
    protocol.require(
        run,
        "read-case",
        read.status == 200
        and [i["itemId"] for i in r["items"]] == [light_id, door_id]
        and progress(r) == ZERO_OF_TWO,
        "200, same two items, 0/2",
        f"{read.status} {progress(r) if r else None}",
    )

    in_queue = queue_contains(dispatcher, house_a, case_id)
    protocol.require(run, "dispatcher-queue", in_queue, "case in NEEDS_ACCEPTANCE", in_queue)

    accept_body = {"expectedCaseVersion": c["caseVersion"]}
    accepted = dispatcher.call("POST", f"{API}/cases/{case_id}/acceptance", accept_body, key("accept"))
    c = accepted.json or {}
    receipt = c.get("receipt") or {}
    protocol.require(
        run,
        "accept-case",
        accepted.status == 200
        and bool(receipt.get("acceptedAt"))
        and receipt.get("acceptedBy") == dispatcher_id
        and progress(c) == ZERO_OF_TWO,
        "200, acceptedAt, acceptedBy=dispatcher, 0/2",
        f"{accepted.status} acceptedBy={'dispatcher' if receipt.get('acceptedBy') == dispatcher_id else receipt.get('acceptedBy')}",
    )
    accepted_json = c
    still_queued = queue_contains(dispatcher, house_a, case_id)
    protocol.check(run, "queue-after-accept", not still_queued, "not in NEEDS_ACCEPTANCE", still_queued)

    due = (datetime.now(UTC).replace(microsecond=0) + timedelta(days=1)).isoformat().replace("+00:00", "Z")
    door = item_of(c, door_id)
    step = dispatcher.call(
        "PUT",
        f"{API}/items/{door_id}/next-action",
        {
            "expectedCaseVersion": c["caseVersion"],
            "expectedItemVersion": door["itemVersion"],
            "text": NEXT_ACTION_TEXT,
            "ownerId": dispatcher_id,
            "updateDueAt": due,
        },
        key("door-next-action"),
    )
    c = step.json or {}
    door, light = item_of(c, door_id), item_of(c, light_id)
    plan = door.get("nextAction") or {}
    protocol.require(
        run,
        "set-door-next-action",
        step.status == 200
        and plan.get("text") == NEXT_ACTION_TEXT
        and plan.get("ownerId") == dispatcher_id
        and parse_instant(plan.get("updateDueAt")) == parse_instant(due)
        and light.get("nextAction") is None
        and (door["workStatus"], door["observation"]) == ("NOT_ASSIGNED", "NOT_RECEIVED")
        and (light["workStatus"], light["observation"]) == ("NOT_ASSIGNED", "NOT_RECEIVED")
        and progress(c) == ZERO_OF_TWO,
        "200, step only on the door, owner dispatcher, both NOT_ASSIGNED/NOT_RECEIVED, 0/2",
        f"{step.status} door={door['workStatus']} light.nextAction={light.get('nextAction')}",
    )
    door_baseline = door
    items_before_report = c["items"]

    saved = dispatcher.call(
        "POST",
        f"{API}/cases/{case_id}/reports",
        {
            "expectedCaseVersion": c["caseVersion"],
            "reportText": REPORT_TEXT,
            "targetItemIds": [light_id, door_id],
        },
        key("report-light"),
    )
    s = saved.json or {}
    report = s.get("report") or {}
    c = s.get("case") or {}
    protocol.require(
        run,
        "save-light-report",
        saved.status == 201
        and report.get("links") == []
        and set(report.get("targetItemIds") or []) == {light_id, door_id}
        and c.get("items") == items_before_report
        and progress(c) == ZERO_OF_TWO
        and (s.get("analysis") or {}).get("status") in {"QUEUED", "MANUAL_REQUIRED"},
        "201, no links, scope light+door, every item unchanged, 0/2",
        f"{saved.status} links={report.get('links')} analysis={(s.get('analysis') or {}).get('status')}",
    )
    report_id = report["reportId"]

    got = author.call("GET", f"{API}/reports/{report_id}")
    g = got.json or {}
    protocol.require(
        run,
        "read-saved-report",
        got.status == 200
        and g.get("reportId") == report_id
        and g.get("caseId") == case_id
        and g.get("reportText") == REPORT_TEXT
        and set(g.get("targetItemIds") or []) == {light_id, door_id}
        and g.get("links") == [],
        "200 for the author, same report, scope, no links",
        f"{got.status} links={g.get('links')}",
    )

    light = item_of(c, light_id)
    linked = dispatcher.call(
        "POST",
        f"{API}/reports/{report_id}/links",
        {
            "expectedCaseVersion": c["caseVersion"],
            "links": [
                {
                    "itemId": light_id,
                    "expectedItemVersion": light["itemVersion"],
                    "contentVersion": light["contentVersion"],
                    "expectedWorkRound": light["workRound"],
                    "workStatus": "REPORTED_DONE",
                }
            ],
        },
        key("link-light"),
    )
    c = linked.json or {}
    new_light = item_of(c, light_id)
    protocol.require(
        run,
        "link-report-to-light",
        linked.status == 200
        and new_light["workStatus"] == "REPORTED_DONE"
        and new_light["workRound"] == light["workRound"] + 1
        and new_light["observation"] == "NOT_RECEIVED"
        and item_of(c, door_id) == door_baseline
        and progress(c) == ZERO_OF_TWO,
        "200, light REPORTED_DONE in a new round, door identical, 0/2",
        f"{linked.status} light={new_light['workStatus']}/round {new_light['workRound']}",
    )
    light = new_light

    observe_body = {
        "expectedCaseVersion": c["caseVersion"],
        "expectedItemVersion": light["itemVersion"],
        "contentVersion": light["contentVersion"],
        "workRound": light["workRound"],
        "value": "RESTORED",
        "source": "AFTER_REPORT",
    }
    observed = author.call("POST", f"{API}/items/{light_id}/observations", observe_body, key("observe-light"))
    c = observed.json or {}
    final_light = item_of(c, light_id)
    protocol.require(
        run,
        "observe-light-restored",
        observed.status == 200
        and final_light["observation"] == "RESTORED"
        and final_light["workStatus"] == "REPORTED_DONE"
        and final_light["workRound"] == light["workRound"]
        and item_of(c, door_id) == door_baseline
        and progress(c) == ONE_OF_TWO
        and types_ok(c),
        "200, light RESTORED in the same round, door identical, 1/2",
        f"{observed.status} light={final_light['observation']} progress={progress(c)}",
    )
    observed_json = c

    for role_name, client in (("author", author), ("dispatcher", dispatcher)):
        again = client.call("GET", f"{API}/cases/{case_id}")
        protocol.require(
            run,
            f"read-partial-result-{role_name}",
            again.status == 200 and again.json == observed_json,
            "200, the stored 1/2 exactly as answered",
            f"{again.status} progress={progress(again.json) if again.json else None}",
        )

    def unchanged(check_id):
        now = author.call("GET", f"{API}/cases/{case_id}")
        protocol.require(
            run, check_id, now.status == 200 and now.json == observed_json, "state identical", f"{now.status}"
        )

    replay = author.call("POST", f"{API}/items/{light_id}/observations", observe_body, key("observe-light"))
    protocol.require(
        run,
        "replay-observe",
        replay.status == 200 and replay.json == observed_json,
        "200, the stored answer",
        replay.status,
    )
    unchanged("replay-observe-no-effect")

    other_value = dict(observe_body, value="PERSISTS")
    reused = author.call("POST", f"{API}/items/{light_id}/observations", other_value, key("observe-light"))
    expect_problem(protocol, run, "same-key-other-body", reused, 409, "IDEMPOTENCY_KEY_REUSED")
    unchanged("same-key-other-body-no-effect")

    for check_id, path in (
        ("stranger-read-case", f"{API}/cases/{case_id}"),
        ("stranger-read-report", f"{API}/reports/{report_id}"),
        ("stranger-read-history", f"{API}/cases/{case_id}/history?limit=50"),
    ):
        expect_problem(protocol, run, check_id, stranger.call("GET", path), 404, "RESOURCE_NOT_FOUND")
    foreign = stranger.call(
        "POST",
        f"{API}/items/{light_id}/observations",
        dict(observe_body, value="PERSISTS"),
        key("stranger-observe"),
    )
    expect_problem(protocol, run, "stranger-observe", foreign, 404, "RESOURCE_NOT_FOUND")
    unchanged("stranger-no-effect")

    staff = dispatcher.call(
        "POST",
        f"{API}/items/{light_id}/observations",
        dict(observe_body, value="PERSISTS"),
        key("dispatcher-observe"),
    )
    expect_problem(protocol, run, "dispatcher-observe", staff, 403, "ACTION_FORBIDDEN")
    unchanged("dispatcher-observe-no-effect")

    door = item_of(observed_json, door_id)
    stale = author.call(
        "POST",
        f"{API}/items/{door_id}/observations",
        {
            "expectedCaseVersion": accepted_json["caseVersion"],
            "expectedItemVersion": door["itemVersion"],
            "contentVersion": door["contentVersion"],
            "workRound": door["workRound"],
            "value": "PERSISTS",
            "source": "INDEPENDENT",
        },
        key("stale-door"),
    )
    expect_problem(protocol, run, "stale-case-version", stale, 409, "CASE_VERSION_CONFLICT")
    unchanged("stale-case-version-no-effect")

    twice = dispatcher.call("POST", f"{API}/cases/{case_id}/acceptance", accept_body, key("accept-again"))
    expect_problem(protocol, run, "accept-again-new-key", twice, 422, "INVALID_TRANSITION")
    same = dispatcher.call("POST", f"{API}/cases/{case_id}/acceptance", accept_body, key("accept"))
    protocol.require(
        run,
        "replay-accept",
        same.status == 200 and same.json == accepted_json,
        "200, the stored first acceptance",
        same.status,
    )
    unchanged("accept-attempts-no-effect")

    events = history_of(dispatcher, case_id)
    types = [event["eventType"] for event in events]
    by_type = {event["eventType"]: event.get("details") or {} for event in events}
    details_ok = (
        by_type.get("ITEM_NEXT_ACTION_SET") == {"itemId": door_id, "ownerId": dispatcher_id}
        and by_type.get("REPORT_SAVED") == {"reportId": report_id, "targetCount": 2}
        and by_type.get("REPORT_LINK_CONFIRMED")
        == {
            "reportId": report_id,
            "itemId": light_id,
            "workRound": light["workRound"],
            "workStatus": "REPORTED_DONE",
        }
        and by_type.get("OBSERVATION_RECORDED")
        == {
            "itemId": light_id,
            "workRound": light["workRound"],
            "value": "RESTORED",
            "source": "AFTER_REPORT",
        }
    )
    no_text = all(
        REPORT_TEXT not in json.dumps(e, ensure_ascii=False) and run_id not in json.dumps(e) for e in events
    )
    protocol.require(
        run,
        "case-history",
        types == EVENTS and details_ok and no_text,
        "one event per command in order, exact details, no free text",
        types,
    )
    return case_id


def body_request_id_check(protocol):
    protocol.check(
        "all",
        "problem-body-request-id",
        not MISSING_BODY_REQUEST_ID,
        "every refusal body carries its X-Request-Id",
        f"missing in {len(MISSING_BODY_REQUEST_ID)}: {', '.join(sorted({m.split(':')[1] for m in MISSING_BODY_REQUEST_ID}))}"
        if MISSING_BODY_REQUEST_ID
        else "all present",
    )


def main():
    if args.runs < 1 or args.runs > 10:
        raise SystemExit("--runs must be 1..10")
    protocol = Protocol()
    stand = Stand()
    started = datetime.now(UTC)
    case_ids = []
    leaked = []
    try:
        stand.up()
        public_checks(stand, protocol)
        clients, roles, house_a, codes = prepare_roles(stand, protocol)
        for run_no in range(1, args.runs + 1):
            try:
                case_ids.append(scenario(run_no, clients, roles, house_a, protocol))
            except CheckFailed as failure:
                print(f"run{run_no} stopped at {failure}; dependent checks skipped", flush=True)
        body_request_id_check(protocol)
        protocol.check(
            "all",
            "runs-completed-with-distinct-cases",
            len(set(case_ids)) == len(case_ids) == args.runs,
            f"{args.runs} runs, {args.runs} different cases",
            f"{len(case_ids)} runs, {len(set(case_ids))} cases",
        )
        logs = stand.logs()
        sensitive = [stand.bot_token, *codes] + [v for c in clients.values() for v in c.cookie_values()]
        sensitive += [c.csrf[1] for c in clients.values() if c.csrf]
        leaked = [s for s in sensitive if s and len(s) >= 8 and s in logs]
        user_text = "Проверка DATA-API" in logs or REPORT_TEXT in logs
        protocol.check(
            "all",
            "no-secrets-or-text-in-logs",
            not leaked and not user_text,
            "none",
            f"{len(leaked)} secrets",
        )
    except CheckFailed as failure:
        print(f"setup stopped at {failure}", flush=True)
    finally:
        stand.down()

    report_path = (
        Path(args.report)
        if args.report
        else ROOT / "output" / "qa" / ("data-api-" + started.strftime("%Y%m%dT%H%M%SZ") + ".json")
    )
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(
        json.dumps(
            {
                "sourceSha": stand.sha,
                "uncommittedChanges": stand.dirty,
                "startedAt": started.isoformat(),
                "stand": "throwaway compose.release.yaml, synthetic signing key; not MAX, not the public stand",
                "runs": args.runs,
                "checks": protocol.rows,
                "failed": len(protocol.failed),
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    total, failed = len(protocol.rows), len(protocol.failed)
    dirty = " (with uncommitted changes)" if stand.dirty else ""
    print(f"\nSource {stand.sha[:12]}{dirty}; protocol {report_path}")
    print(f"RESULT {'PASS' if failed == 0 and total else 'FAIL'}: {total - failed}/{total} checks")
    raise SystemExit(0 if failed == 0 and total else 1)


main()
