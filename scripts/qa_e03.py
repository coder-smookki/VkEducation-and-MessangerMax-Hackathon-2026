import argparse
import hashlib
import hmac
import json
import subprocess
import time
import urllib.error
import urllib.request
from http.cookiejar import CookieJar
from pathlib import Path
from urllib.parse import quote
from uuid import uuid4

parser = argparse.ArgumentParser()
parser.add_argument("--api-url", default="http://127.0.0.1:8080")
parser.add_argument("--bot-token", default=None)
parser.add_argument("--db-service", default="db")
parser.add_argument("--db-user", default="koren")
parser.add_argument("--db-name", default="koren")
args = parser.parse_args()

ROOT = Path(__file__).resolve().parents[1]


def bot_token() -> str:
    if args.bot_token:
        return args.bot_token
    env = ROOT / ".env"
    if env.exists():
        for line in env.read_text(encoding="utf-8").splitlines():
            if line.startswith("MAX_BOT_TOKEN="):
                token = line.split("=", 1)[1].strip()
                if token:
                    return token
    raise SystemExit("MAX_BOT_TOKEN not found; pass --bot-token or set it in .env")


def sign_init_data(token: str, user_id: int, first_name: str, last_name: str) -> str:
    """Sign the percent-decoded values sorted by key, exactly as the validator checks them."""
    user = json.dumps({"id": user_id, "first_name": first_name, "last_name": last_name,
                       "username": None, "language_code": "ru"},
                      ensure_ascii=False, separators=(",", ":"))
    values = {"auth_date": str(int(time.time())),
              "query_id": "qa-" + uuid4().hex[:16],
              "user": user}
    check = "\n".join(f"{key}={values[key]}" for key in sorted(values))
    secret = hmac.new(b"WebAppData", token.encode("utf-8"), hashlib.sha256).digest()
    digest = hmac.new(secret, check.encode("utf-8"), hashlib.sha256).hexdigest()
    query = "&".join(f"{key}={quote(value, safe='')}" for key, value in values.items())
    return query + "&hash=" + digest


class Client:
    """One signed MAX user with its own cookie jar, session and CSRF token."""

    CSRF_PATH = "/api/v1/csrf"

    def __init__(self, label: str):
        self.label = label
        self.jar = CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))
        self.user_id = None
        self.csrf_header = None
        self.csrf_token = None

    def _send(self, method, path, body=None, key=None, csrf=True):
        data = None if body is None else json.dumps(body).encode("utf-8")
        request = urllib.request.Request(args.api_url + path, data=data, method=method)
        if data is not None:
            request.add_header("Content-Type", "application/json")
        if key is not None:
            request.add_header("Idempotency-Key", key)
        if csrf and self.csrf_token is not None:
            request.add_header(self.csrf_header, self.csrf_token)
        try:
            with self.opener.open(request, timeout=15) as response:
                text = response.read().decode("utf-8")
                return response.status, (json.loads(text) if text else None)
        except urllib.error.HTTPError as error:
            text = error.read().decode("utf-8", "replace")
            raise SystemExit(f"{self.label}: {method} {path} -> {error.code}\n{text}") from error

    def refresh_csrf(self):
        """The masked token lives in the response body; the cookie holds the raw one."""
        _, token = self._send("GET", self.CSRF_PATH, csrf=False)
        self.csrf_header, self.csrf_token = token["headerName"], token["token"]

    def call(self, method, path, body=None, key=None):
        if method != "GET" and path != self.CSRF_PATH:
            self.refresh_csrf()
        return self._send(method, path, body, key)

    def login(self, init_data):
        status, body = self.call("POST", "/api/v1/auth/max", {"initData": init_data})
        if status != 200:
            raise SystemExit(f"{self.label}: login returned {status}")
        self.user_id = body["userId"]
        return self.user_id


def psql(sql: str):
    result = subprocess.run(
        ["docker", "compose", "exec", "-T", args.db_service, "psql",
         "-U", args.db_user, "-d", args.db_name, "-v", "ON_ERROR_STOP=1", "-c", sql],
        cwd=ROOT, capture_output=True, text=True, check=False)
    if result.returncode != 0:
        raise SystemExit("psql failed:\n" + result.stderr.strip())
    return result.stdout


def check(label: str, ok: bool, detail: str = ""):
    checks.append((label, ok, detail))
    print(("  PASS  " if ok else "  FAIL  ") + label + (f"   [{detail}]" if detail else ""))
    return ok


checks = []
token = bot_token()
author, dispatcher = Client("author"), Client("dispatcher")

print("QA-E03: приём обращения и следующий шаг по пункту, настоящий HTTP\n")

print("Дано: действующий автор и диспетчер одного дома")
author.login(sign_init_data(token, 424242, "Житель", "Третьего этажа"))
dispatcher.login(sign_init_data(token, 31337, "Диспетчер", "Дома"))
house = str(uuid4())
psql(f"INSERT INTO house(id, address) VALUES ('{house}', 'QA-E03 {house[:8]}')")
psql(f"INSERT INTO membership VALUES ('{author.user_id}','{house}','RESIDENT'),"
     f" ('{dispatcher.user_id}','{house}','DISPATCHER')")
print(f"  автор {author.user_id}\n  диспетчер {dispatcher.user_id}\n  дом {house}\n")

print("Автор заводит обращение с двумя проблемами и подтверждает список")
_, case = author.call("POST", "/api/v1/cases", key=str(uuid4()), body={
    "houseId": house, "sourceText": "На третьем этаже темно, и входная дверь не закрывается."})
case_id = case["caseId"]
_, case = author.call("PATCH", f"/api/v1/cases/{case_id}/items", key=str(uuid4()), body={
    "expectedCaseVersion": case["caseVersion"],
    "items": [{"itemId": None, "category": "LIGHTING", "location": "Этаж 3",
               "symptom": "Не горит свет"},
              {"itemId": None, "category": "ENTRANCE_DOOR", "location": "Вход",
               "symptom": "Дверь не закрывается"}]})
_, case = author.call("POST", f"/api/v1/cases/{case_id}/confirm", key=str(uuid4()), body={
    "expectedCaseVersion": case["caseVersion"], "sourceRevision": case["sourceRevision"]})
door = next(i for i in case["items"] if i["category"] == "ENTRANCE_DOOR")
light = next(i for i in case["items"] if i["category"] == "LIGHTING")
check("CONFIRMED виден диспетчеру", dispatcher.call("GET", f"/api/v1/cases/{case_id}")[0] == 200)
check("обращение стоит в очереди NEEDS_ACCEPTANCE", any(
    c["caseId"] == case_id for c in dispatcher.call(
        "GET", f"/api/v1/cases?houseId={house}&queueState=NEEDS_ACCEPTANCE")[1]["items"]))
print()

print("Действие: диспетчер принимает обращение")
_, accepted = dispatcher.call("POST", f"/api/v1/cases/{case_id}/acceptance", key=str(uuid4()),
                              body={"expectedCaseVersion": case["caseVersion"]})
receipt = accepted["receipt"]
check("acceptedAt проставлен", receipt["acceptedAt"] is not None, str(receipt["acceptedAt"]))
check("acceptedBy - принявший диспетчер", receipt["acceptedBy"] == dispatcher.user_id)
check("confirmedAt сохранён отдельно от приёма", receipt["confirmedAt"] is not None)
check("приём не тронул работы", all(i["workStatus"] == "NOT_ASSIGNED" for i in accepted["items"]))
check("приём не тронул наблюдение", all(i["observation"] == "NOT_RECEIVED" for i in accepted["items"]))
check("обращение ушло из NEEDS_ACCEPTANCE", not any(
    c["caseId"] == case_id for c in dispatcher.call(
        "GET", f"/api/v1/cases?houseId={house}&queueState=NEEDS_ACCEPTANCE")[1]["items"]))
print()

print("Действие: диспетчер задаёт двери действие, ответственного и дату обновления")
due = "2030-01-20T12:00:00Z"
_, planned = dispatcher.call("PUT", f"/api/v1/items/{door['itemId']}/next-action", key=str(uuid4()),
                             body={"expectedCaseVersion": accepted["caseVersion"],
                                   "expectedItemVersion": door["itemVersion"],
                                   "text": "Вызвать мастера по замку",
                                   "ownerId": dispatcher.user_id, "updateDueAt": due})
planned_door = next(i for i in planned["items"] if i["itemId"] == door["itemId"])
planned_light = next(i for i in planned["items"] if i["itemId"] == light["itemId"])
check("план сохранён у двери", planned_door["nextAction"] is not None)
check("текст плана тот, что отправили",
      planned_door["nextAction"]["text"] == "Вызвать мастера по замку")
check("ответственный - диспетчер", planned_door["nextAction"]["ownerId"] == dispatcher.user_id)
check("дата обновления та, что отправили", planned_door["nextAction"]["updateDueAt"] == due)
check("updatedAt подписан сервером", planned_door["nextAction"]["updatedAt"] is not None)
check("у света плана нет", planned_light["nextAction"] is None)
check("план поднял itemVersion двери", planned_door["itemVersion"] == door["itemVersion"] + 1)
check("план не тронул itemVersion света", planned_light["itemVersion"] == light["itemVersion"])
check("план не тронул contentVersion двери",
      planned_door["contentVersion"] == door["contentVersion"])
check("план не тронул workRound двери", planned_door["workRound"] == door["workRound"])
check("план не означает выполненных работ", planned_door["workStatus"] == "NOT_ASSIGNED")
print()

print("Ожидается: автор после перечитывания видит настоящие данные")
_, reread = author.call("GET", f"/api/v1/cases/{case_id}")
reread_door = next(i for i in reread["items"] if i["itemId"] == door["itemId"])
check("автор видит receipt", reread["receipt"]["acceptedAt"] == receipt["acceptedAt"])
check("автор видит план по двери", reread_door["nextAction"]["text"] == "Вызвать мастера по замку")
check("автор видит дату обновления", reread_door["nextAction"]["updateDueAt"] == due)
check("номер обращения человекочитаем", bool(reread["displayNumber"]),
      str(reread["displayNumber"]))
check("прогресс не вырос от приёма и плана",
      reread["progress"] == {"total": 2, "confirmed": 0, "withdrawn": 0,
                             "remaining": 2, "allConfirmed": False})
print()

failed = [label for label, ok, _ in checks if not ok]
print(f"Обращение {case_id}, дом {house}")
if failed:
    raise SystemExit(f"QA-E03 FAIL: {len(failed)} из {len(checks)}\n  " + "\n  ".join(failed))
print(f"QA-E03 PASS: {len(checks)} проверок. Реальный MAX-транспорт этим не подтверждается.")
