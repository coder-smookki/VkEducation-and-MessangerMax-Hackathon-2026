"""Minimal seed for a synthetic stand (S-08): a house, invitations for residents, a dispatcher.

    python3 scripts/seed_stand.py house "Синтетическая, 1"
    python3 scripts/seed_stand.py invite <houseId> --count 2 --days 7
    python3 scripts/seed_stand.py dispatcher <houseId> <maxUserId>
    python3 scripts/seed_stand.py apply    # both test houses and roles from scripts/seed/seed.json
    python3 scripts/seed_stand.py check    # who holds which role there; exits 1 on any extra right

apply and check read the MAX ids of the test accounts from scripts/seed/accounts.private.json (not in
Git; copy accounts.private.example.json). apply is safe to repeat: houses are created once, a
dispatcher is (re)assigned, and a resident who has not joined yet gets a fresh one-time invitation.

Residents join only through an invitation and POST /api/v1/memberships/claim, as in production. A
dispatcher is the one elevated role, set by the technical operator here: the person must have signed
in through MAX once, so that their account exists. Nothing is ever deleted, and no role or user id is
taken from a request.

An invitation code is printed once and never stored: the database keeps only its SHA-256, exactly as
InvitationService.hash computes it. Hand codes over through a private channel, never a public chat.
Run only against a stand with synthetic data.
"""

import argparse
import hashlib
import json
import secrets
import subprocess
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SEED = ROOT / "scripts" / "seed" / "seed.json"
ACCOUNTS = ROOT / "scripts" / "seed" / "accounts.private.json"

parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
parser.add_argument("--db-service", default="db")
parser.add_argument("--db-user", default="koren")
parser.add_argument("--db-name", default="koren")
commands = parser.add_subparsers(dest="command", required=True)
house_cmd = commands.add_parser("house", help="create a house and print its id")
house_cmd.add_argument("address")
house_cmd.add_argument("--review", action="store_true", help="mark a NEW house as an isolated reviewer house")
invite_cmd = commands.add_parser("invite", help="issue one-time RESIDENT invitations for a house")
invite_cmd.add_argument("house_id", type=uuid.UUID)
invite_cmd.add_argument("--count", type=int, default=1)
invite_cmd.add_argument("--days", type=int, default=7)
invite_cmd.add_argument("--role", choices=["RESIDENT", "DISPATCHER"], default="RESIDENT")
dispatcher_cmd = commands.add_parser("dispatcher", help="make a signed-in MAX user a dispatcher of a house")
dispatcher_cmd.add_argument("house_id", type=uuid.UUID)
dispatcher_cmd.add_argument("max_user_id", type=int)
apply_cmd = commands.add_parser("apply", help="create the test houses and roles described in seed.json")
apply_cmd.add_argument("--days", type=int, default=7)
commands.add_parser("check", help="list the roles in the test houses; exit 1 on an unexpected one")
args = parser.parse_args()


def psql(sql: str, **variables) -> str:
    """Values travel as psql variables and are quoted by psql itself (:'name'), never pasted into SQL."""
    command = [
        "docker",
        "compose",
        "exec",
        "-T",
        args.db_service,
        "psql",
        "-U",
        args.db_user,
        "-d",
        args.db_name,
    ]
    command += ["-v", "ON_ERROR_STOP=1", "-At", "-q"]
    for name, value in variables.items():
        command += ["-v", f"{name}={value}"]
    result = subprocess.run(command, cwd=ROOT, input=sql, capture_output=True, text=True, check=False)
    if result.returncode != 0:
        raise SystemExit("psql failed:\n" + result.stderr.strip())
    return result.stdout.strip()


def code_hash(code: str) -> str:
    """Same as InvitationService.hash: SHA-256 of the stripped UTF-8 code, lowercase hex."""
    return hashlib.sha256(code.strip().encode("utf-8")).hexdigest()


def require_house(house_id):
    if psql("SELECT count(*) FROM house WHERE id = :'house';", house=house_id) != "1":
        raise SystemExit(f"No house {house_id}")


def house():
    house_id = uuid.uuid4()
    psql(
        "INSERT INTO house(id, address, review_only) VALUES (:'house', :'address', :review);",
        house=house_id, address=args.address, review=str(args.review).lower()
    )
    print(f"house {house_id}")


def invite():
    require_house(args.house_id)
    if not 1 <= args.count <= 50 or not 1 <= args.days <= 30:
        raise SystemExit("--count must be 1..50 and --days 1..30")
    if args.role == "DISPATCHER" and psql(
        "SELECT review_only FROM house WHERE id = :'house';", house=args.house_id
    ) != "t":
        raise SystemExit("DISPATCHER invitations are permitted only in a separately created reviewer house")
    print(f"{args.role} invitations for house {args.house_id}, valid {args.days} days, one use each:")
    for _ in range(args.count):
        code = secrets.token_urlsafe(18)
        psql(
            "INSERT INTO house_invitation(id, house_id, code_hash, expires_at, granted_role)"
            " VALUES (:'id', :'house', :'hash', now() + make_interval(days => :days), :'role');",
            id=uuid.uuid4(),
            house=args.house_id,
            hash=code_hash(code),
            days=args.days,
            role=args.role,
        )
        print(f"  {code}")
    print("Codes are not stored anywhere; lost ones are simply issued again.")


def dispatcher():
    require_house(args.house_id)
    user_id = psql("SELECT id FROM app_user WHERE max_user_id = :max;", max=args.max_user_id)
    if not user_id:
        raise SystemExit(
            f"MAX user {args.max_user_id} has never signed in; ask them to open the Mini App first"
        )
    psql(
        "INSERT INTO membership(user_id, house_id, role) VALUES (:'user', :'house', 'DISPATCHER')"
        " ON CONFLICT (user_id, house_id) DO UPDATE SET role = 'DISPATCHER', revoked_at = NULL;",
        user=user_id,
        house=args.house_id,
    )
    print(f"user {user_id} (MAX {args.max_user_id}) is a dispatcher of house {args.house_id}")


def issue_invitation(house_id, days) -> str:
    code = secrets.token_urlsafe(18)
    psql(
        "INSERT INTO house_invitation(id, house_id, code_hash, expires_at)"
        " VALUES (:'id', :'house', :'hash', now() + make_interval(days => :days));",
        id=uuid.uuid4(),
        house=house_id,
        hash=code_hash(code),
        days=days,
    )
    return code


def load_seed():
    seed = json.loads(SEED.read_text(encoding="utf-8"))
    if not ACCOUNTS.exists():
        raise SystemExit(
            f"No {ACCOUNTS.relative_to(ROOT)}: copy accounts.private.example.json and fill MAX ids"
        )
    try:
        ids = json.loads(ACCOUNTS.read_text(encoding="utf-8"))
    except ValueError as error:
        raise SystemExit(
            f"accounts.private.json is not valid JSON ({error}); MAX ids are plain numbers"
        ) from None
    houses = {house_row["key"]: house_row for house_row in seed["houses"]}
    for account in seed["accounts"]:
        max_id = ids.get(account["account"])
        if not isinstance(max_id, int) or max_id <= 0:
            raise SystemExit(f"accounts.private.json: no MAX id for '{account['account']}'")
        account["max_user_id"] = max_id
        account["house_id"] = houses[account["house"]]["id"]
    if len({account["max_user_id"] for account in seed["accounts"]}) != len(seed["accounts"]):
        raise SystemExit("accounts.private.json: each role needs its own MAX account")
    return seed


def user_of(max_user_id):
    return psql("SELECT id FROM app_user WHERE max_user_id = :max;", max=max_user_id)


def active_role(user_id, house_id):
    return psql(
        "SELECT role FROM membership WHERE user_id = :'user' AND house_id = :'house' AND revoked_at IS NULL;",
        user=user_id,
        house=house_id,
    )


def apply():
    if not 1 <= args.days <= 30:
        raise SystemExit("--days must be 1..30")
    seed = load_seed()
    for house_row in seed["houses"]:
        psql(
            "INSERT INTO house(id, address) VALUES (:'house', :'address') ON CONFLICT (id) DO NOTHING;",
            house=house_row["id"],
            address=house_row["address"],
        )
        print(f"house {house_row['key']} {house_row['id']} - {house_row['address']}")
    pending = 0
    for account in seed["accounts"]:
        name, house_id, role = account["account"], account["house_id"], account["role"]
        user_id = user_of(account["max_user_id"])
        if not user_id:
            print(f"  {name}: has not opened the Mini App yet - open it once, then run apply again")
            pending += 1
            continue
        if role == "DISPATCHER":
            psql(
                "INSERT INTO membership(user_id, house_id, role) VALUES (:'user', :'house', 'DISPATCHER')"
                " ON CONFLICT (user_id, house_id) DO UPDATE SET role = 'DISPATCHER', revoked_at = NULL;",
                user=user_id,
                house=house_id,
            )
            print(f"  {name}: DISPATCHER of house {account['house']}")
        elif active_role(user_id, house_id) == "RESIDENT":
            print(f"  {name}: already a RESIDENT of house {account['house']}")
        else:
            # Residents still join the way real ones do: one-time code, POST /memberships/claim.
            code = issue_invitation(house_id, args.days)
            print(f"  {name}: invitation to house {account['house']}, {args.days} days, one use: {code}")
            pending += 1
    print("Hand codes over through a private channel only. Then run: python3 scripts/seed_stand.py check")
    if pending:
        print(f"{pending} account(s) still to join; check will list them as missing")


def check():
    seed = load_seed()
    expected = {(a["max_user_id"], a["house_id"]): a for a in seed["accounts"]}
    names = {a["max_user_id"]: a["account"] for a in seed["accounts"]}
    problems = 0
    for house_row in seed["houses"]:
        rows = psql(
            "SELECT u.max_user_id, m.role FROM membership m JOIN app_user u ON u.id = m.user_id"
            " WHERE m.house_id = :'house' AND m.revoked_at IS NULL ORDER BY m.role, u.max_user_id;",
            house=house_row["id"],
        )
        print(f"house {house_row['key']} {house_row['id']}")
        seen = set()
        for line in filter(None, rows.splitlines()):
            max_text, role = line.split("|")
            max_id = int(max_text)
            want = expected.get((max_id, house_row["id"]))
            who = names.get(max_id, "not a test account")
            if want and want["role"] == role:
                print(f"  OK     {role:<10} {who}")
                seen.add(max_id)
            else:
                print(f"  EXTRA  {role:<10} {who} (MAX {max_id})")
                problems += 1
        for account in seed["accounts"]:
            if account["house_id"] == house_row["id"] and account["max_user_id"] not in seen:
                print(f"  MISSING {account['role']:<9} {account['account']}")
                problems += 1
    print(
        "PASS: every test role is in place and nobody has extra rights"
        if not problems
        else f"FAIL: {problems} problem(s) above"
    )
    raise SystemExit(1 if problems else 0)


{"house": house, "invite": invite, "dispatcher": dispatcher, "apply": apply, "check": check}[args.command]()
