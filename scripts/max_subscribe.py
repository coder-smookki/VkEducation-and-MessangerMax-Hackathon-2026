#!/usr/bin/env python3
"""Webhook subscription of the MAX bot: list, subscribe, unsubscribe.

The token and the secret are read from the environment only (MAX_BOT_TOKEN, MAX_WEBHOOK_SECRET) and
are never printed. TLS is verified against the system roots plus the Russian Trusted Root CA that
the backend bundles; verification is never switched off.

    MAX_BOT_TOKEN=... python3 scripts/max_subscribe.py list
    MAX_BOT_TOKEN=... MAX_WEBHOOK_SECRET=... python3 scripts/max_subscribe.py subscribe
    MAX_BOT_TOKEN=... python3 scripts/max_subscribe.py unsubscribe
"""

from __future__ import annotations

import argparse
import json
import os
import re
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

API_URL = "https://platform-api2.max.ru"
WEBHOOK_URL = "https://hackathon.lumnex.ru/max/webhook"
UPDATE_TYPES = ["message_created", "bot_started"]
# MAX accepts only this alphabet and length for the secret it echoes in X-Max-Bot-Api-Secret.
SECRET_PATTERN = re.compile(r"^[A-Za-z0-9_-]{5,256}$")
BUNDLED_CA = (
    Path(__file__).resolve().parent.parent
    / "backend/src/main/resources/certs/russian_trusted_ca.pem"
)
TIMEOUT_SECONDS = 15


def tls_context() -> ssl.SSLContext:
    context = ssl.create_default_context()
    if BUNDLED_CA.is_file():
        context.load_verify_locations(cafile=str(BUNDLED_CA))
    return context


def call(method: str, url: str, token: str, body: dict | None = None) -> tuple[int, object]:
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("Authorization", token)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    context = tls_context() if url.startswith("https://") else None
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS, context=context) as response:
            return response.status, json.loads(response.read() or b"null")
    except urllib.error.HTTPError as error:
        # The body of an error is MAX's own message, not ours; the token is never in it.
        raw = error.read()
        try:
            return error.code, json.loads(raw or b"null")
        except json.JSONDecodeError:
            return error.code, raw.decode(errors="replace")[:500]


def env(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        sys.exit(f"{name} is not set")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("action", choices=["list", "subscribe", "unsubscribe"])
    parser.add_argument(
        "--url", default=WEBHOOK_URL, help=f"webhook address (default {WEBHOOK_URL})"
    )
    parser.add_argument("--api-url", default=os.environ.get("MAX_API_URL", API_URL))
    parser.add_argument(
        "--types", default=",".join(UPDATE_TYPES), help="comma-separated update types for subscribe"
    )
    args = parser.parse_args()

    token = env("MAX_BOT_TOKEN")
    api = args.api_url.rstrip("/")

    if args.action == "list":
        status, answer = call("GET", f"{api}/subscriptions", token)
    elif args.action == "subscribe":
        if not args.url.startswith("https://"):
            sys.exit("MAX delivers webhooks only to https:// on port 443")
        secret = env("MAX_WEBHOOK_SECRET")
        if not SECRET_PATTERN.fullmatch(secret):
            sys.exit("MAX_WEBHOOK_SECRET must be 5-256 characters of A-Z a-z 0-9 _ -")
        types = [kind.strip() for kind in args.types.split(",") if kind.strip()]
        status, answer = call(
            "POST",
            f"{api}/subscriptions",
            token,
            {"url": args.url, "update_types": types, "secret": secret},
        )
    else:
        query = urllib.parse.urlencode({"url": args.url})
        status, answer = call("DELETE", f"{api}/subscriptions?{query}", token)

    print(f"HTTP {status}")
    print(json.dumps(answer, ensure_ascii=False, indent=2))
    # MAX answers 200 even when it refuses; success=false is a failure too.
    refused = isinstance(answer, dict) and answer.get("success") is False
    return 0 if status == 200 and not refused else 1


if __name__ == "__main__":
    sys.exit(main())
