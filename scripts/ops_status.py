"""What the operator needs during S-09/S-10 and the evaluation: version, queues and failures, in one screen.

    python3 scripts/ops_status.py

Runs on the stand host next to docker compose and reads PostgreSQL directly, so it adds no public endpoint.
Prints only counts and ages: no text, no user ids. Paste the output into docs/operations-log.md when
something looks wrong; the questions it answers are "is work stuck" and "did something stop being sent".
"""

import argparse
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
parser.add_argument("--db-service", default="db")
parser.add_argument("--db-user", default="koren")
parser.add_argument("--db-name", default="koren")
args = parser.parse_args()

QUERIES = [
    (
        "migration",
        "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
    ),
    ("database size", "SELECT pg_size_pretty(pg_database_size(current_database()))"),
    (
        "cases draft / confirmed",
        (
            "SELECT count(*) FILTER (WHERE submission_status = 'DRAFT') || ' / '"
            " || count(*) FILTER (WHERE submission_status = 'CONFIRMED') FROM service_case"
        ),
    ),
    (
        "outbox pending / failed / sent",
        (
            "SELECT count(*) FILTER (WHERE sent_at IS NULL AND failed_at IS NULL AND available_at < now() + interval '1 year')"
            " || ' / ' || count(*) FILTER (WHERE failed_at IS NOT NULL)"
            " || ' / ' || count(*) FILTER (WHERE sent_at IS NOT NULL) FROM outbox_event"
        ),
    ),
    (
        "outbox cancelled by access",
        (
            "SELECT count(*) FROM outbox_event WHERE sent_at IS NULL AND failed_at IS NULL"
            " AND available_at >= now() + interval '1 year'"
        ),
    ),
    (
        "outbox oldest pending",
        (
            "SELECT coalesce(date_trunc('second', now() - min(created_at))::text, '-') FROM outbox_event"
            " WHERE sent_at IS NULL AND failed_at IS NULL AND available_at < now() + interval '1 year'"
        ),
    ),
    (
        "outbox failure reasons",
        (
            "SELECT coalesce(string_agg(last_error || ' x' || n, ', ' ORDER BY n DESC), '-') FROM ("
            " SELECT last_error, count(*) AS n FROM outbox_event WHERE failed_at IS NOT NULL GROUP BY last_error) r"
        ),
    ),
    (
        "inbox pending / parked",
        (
            "SELECT count(*) FILTER (WHERE processed_at IS NULL AND attempts < 5) || ' / '"
            " || count(*) FILTER (WHERE processed_at IS NULL AND attempts >= 5) FROM inbox_event"
        ),
    ),
    (
        "analysis queued / ready / manual",
        (
            "SELECT count(*) FILTER (WHERE status = 'QUEUED') || ' / ' || count(*) FILTER (WHERE status = 'READY')"
            " || ' / ' || count(*) FILTER (WHERE status = 'MANUAL_REQUIRED') FROM report_analysis"
        ),
    ),
    (
        "audit events last hour",
        "SELECT count(*) FROM audit_event WHERE created_at > now() - interval '1 hour'",
    ),
]


def psql(sql):
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
    result = subprocess.run(
        command + ["-v", "ON_ERROR_STOP=1", "-At", "-c", sql],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    return (
        result.stdout.strip() if result.returncode == 0 else "ERROR " + result.stderr.strip().splitlines()[-1]
    )


for label, sql in QUERIES:
    print(f"{label:34} {psql(sql)}")
