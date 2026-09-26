"""Minimal seed for a synthetic stand (S-08): a house, invitations for residents, a dispatcher.

    python3 scripts/seed_stand.py house "Синтетическая, 1"
    python3 scripts/seed_stand.py invite <houseId> --count 2 --days 7
    python3 scripts/seed_stand.py dispatcher <houseId> <maxUserId>

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
import secrets
import subprocess
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
parser.add_argument("--db-service", default="db")
parser.add_argument("--db-user", default="koren")
parser.add_argument("--db-name", default="koren")
commands = parser.add_subparsers(dest="command", required=True)
house_cmd = commands.add_parser("house", help="create a house and print its id")
house_cmd.add_argument("address")
invite_cmd = commands.add_parser("invite", help="issue one-time RESIDENT invitations for a house")
invite_cmd.add_argument("house_id", type=uuid.UUID)
invite_cmd.add_argument("--count", type=int, default=1)
invite_cmd.add_argument("--days", type=int, default=7)
dispatcher_cmd = commands.add_parser("dispatcher", help="make a signed-in MAX user a dispatcher of a house")
dispatcher_cmd.add_argument("house_id", type=uuid.UUID)
dispatcher_cmd.add_argument("max_user_id", type=int)
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
        "INSERT INTO house(id, address) VALUES (:'house', :'address');", house=house_id, address=args.address
    )
    print(f"house {house_id}")


def invite():
    require_house(args.house_id)
    if not 1 <= args.count <= 50 or not 1 <= args.days <= 30:
        raise SystemExit("--count must be 1..50 and --days 1..30")
    print(f"Invitations for house {args.house_id}, valid {args.days} days, one use each:")
    for _ in range(args.count):
        code = secrets.token_urlsafe(18)
        psql(
            "INSERT INTO house_invitation(id, house_id, code_hash, expires_at)"
            " VALUES (:'id', :'house', :'hash', now() + make_interval(days => :days));",
            id=uuid.uuid4(),
            house=args.house_id,
            hash=code_hash(code),
            days=args.days,
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


{"house": house, "invite": invite, "dispatcher": dispatcher}[args.command]()
