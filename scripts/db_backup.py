"""Back up, verify and restore the Compose PostgreSQL database (S-07 / QA-R10).

    python3 scripts/db_backup.py backup
    python3 scripts/db_backup.py verify backups/koren-20260927T071500Z.dump
    python3 scripts/db_backup.py restore backups/koren-20260927T071500Z.dump --confirm koren

`verify` restores into a separate throw-away database and compares it with the live one, so it can be
run on a working stand at any time. `restore` replaces the working database and therefore refuses to
run while the backend is up or without the database name typed back.

A dump holds every case text and user id: it stays out of Git (backups/ is ignored) and off shared drives.
"""

import argparse
import subprocess
import sys
import time
from datetime import UTC, datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHECK_DB = "koren_restore_check"

COUNTS_SQL = """
SELECT table_name || '=' || (xpath('/row/c/text()',
       query_to_xml(format('SELECT count(*) AS c FROM %I', table_name), false, true, '')))[1]::text
  FROM information_schema.tables
 WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
 ORDER BY table_name
"""
# Audit is the one thing a restore must never alter: compare it as a whole, not by count alone.
AUDIT_SQL = """
SELECT md5(coalesce(string_agg(id::text || event_type || payload::text || created_at::text, '|'
       ORDER BY id), '')) FROM audit_event
"""
VERSION_SQL = """
SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1
"""

parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
parser.add_argument("--db-service", default="db")
parser.add_argument("--db-user", default="koren")
parser.add_argument("--db-name", default="koren")
commands = parser.add_subparsers(dest="command", required=True)
backup_cmd = commands.add_parser("backup", help="dump the working database to backups/")
backup_cmd.add_argument("--out-dir", default=str(ROOT / "backups"))
verify_cmd = commands.add_parser("verify", help="restore into a throw-away database and compare")
verify_cmd.add_argument("dump")
verify_cmd.add_argument("--keep", action="store_true", help=f"leave {CHECK_DB} for inspection")
restore_cmd = commands.add_parser("restore", help="replace the working database with a dump")
restore_cmd.add_argument("dump")
restore_cmd.add_argument("--confirm", required=True, help="type the working database name to confirm")
args = parser.parse_args()


def compose(*command, stdin=None, stdout=subprocess.PIPE):
    result = subprocess.run(
        ["docker", "compose", *command],
        cwd=ROOT,
        stdin=stdin,
        stdout=stdout,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        raise SystemExit(f"docker compose {command[0]} failed:\n{result.stderr.decode(errors='replace')}")
    return result.stdout.decode(errors="replace") if stdout == subprocess.PIPE else ""


def psql(database, sql):
    return compose(
        "exec",
        "-T",
        args.db_service,
        "psql",
        "-U",
        args.db_user,
        "-d",
        database,
        "-v",
        "ON_ERROR_STOP=1",
        "-At",
        "-c",
        sql,
    ).strip()


def fingerprint(database):
    return {
        "migration": psql(database, VERSION_SQL),
        "audit": psql(database, AUDIT_SQL),
        "rows": psql(database, COUNTS_SQL).splitlines(),
    }


def pg_restore(database, dump, clean):
    options = ["--clean", "--if-exists"] if clean else []
    with open(dump, "rb") as source:
        compose(
            "exec",
            "-T",
            args.db_service,
            "pg_restore",
            "-U",
            args.db_user,
            "-d",
            database,
            "--no-owner",
            "--exit-on-error",
            *options,
            stdin=source,
        )


def require_dump(path):
    dump = Path(path)
    if not dump.is_file():
        raise SystemExit(f"No such dump: {dump}")
    return dump


def backup():
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    target = out_dir / f"{args.db_name}-{datetime.now(UTC):%Y%m%dT%H%M%SZ}.dump"
    started = time.monotonic()
    with open(target, "wb") as sink:
        compose(
            "exec",
            "-T",
            args.db_service,
            "pg_dump",
            "-U",
            args.db_user,
            "-d",
            args.db_name,
            "--format=custom",
            stdout=sink,
        )
    seconds = time.monotonic() - started
    print(f"backup {target} {target.stat().st_size} bytes in {seconds:.1f}s")
    print(f"migration {psql(args.db_name, VERSION_SQL)}")


def verify():
    dump = require_dump(args.dump)
    psql("postgres", f"DROP DATABASE IF EXISTS {CHECK_DB}")
    psql("postgres", f"CREATE DATABASE {CHECK_DB}")
    started = time.monotonic()
    pg_restore(CHECK_DB, dump, clean=False)
    seconds = time.monotonic() - started
    live, restored = fingerprint(args.db_name), fingerprint(CHECK_DB)
    if not args.keep:
        psql("postgres", f"DROP DATABASE {CHECK_DB}")
    print(f"restore into {CHECK_DB} took {seconds:.1f}s")
    for key in ("migration", "audit"):
        print(f"{key}: live={live[key]} restored={restored[key]}")
    differing = sorted(set(live["rows"]) ^ set(restored["rows"]))
    if live == restored:
        print(f"OK: {len(live['rows'])} tables, row counts, migration and audit match the live database")
        return
    # Rows written after the backup was taken also show up here; that is expected on a live stand.
    print("DIFFERENT from the live database:")
    for line in differing:
        print(f"  {line}")
    sys.exit(1)


def restore():
    dump = require_dump(args.dump)
    if args.confirm != args.db_name:
        raise SystemExit(f"--confirm must be the working database name ({args.db_name}); nothing was done")
    if compose("ps", "--status", "running", "--quiet", "backend").strip():
        raise SystemExit("The backend is running; stop it first: docker compose stop backend")
    started = time.monotonic()
    pg_restore(args.db_name, dump, clean=True)
    seconds = time.monotonic() - started
    print(f"restored {args.db_name} from {dump} in {seconds:.1f}s")
    print(f"migration {psql(args.db_name, VERSION_SQL)}; audit {psql(args.db_name, AUDIT_SQL)}")


{"backup": backup, "verify": verify, "restore": restore}[args.command]()
