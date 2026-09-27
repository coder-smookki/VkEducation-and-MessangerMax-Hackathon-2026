"""Build and test the Mini App against a disposable real Java/PostgreSQL stand.

Run after `npm --prefix frontend ci` and `npx --prefix frontend playwright install chromium`.
Creates only synthetic data and a random test-only signing token. Never points at a live database.
The unique Compose project and its volumes are removed in finally, including on a failed test.
Requires Docker Compose and Node.js. Does not exercise a real MAX client or send messages.
"""

import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
project = "koren-ui-check-" + secrets.token_hex(4)
started = time.monotonic()
# Shell settings must not activate HTTPS or replace the disposable signing key.
overrides = {
    "DB_PASSWORD", "MAX_BOT_TOKEN", "MAX_WEBHOOK_SECRET", "MAX_WORKERS_ENABLED",
    "RELEASE_SHA", "ANALYSIS_ENABLED",
    "SESSION_COOKIE_SECURE", "SESSION_COOKIE_SAMESITE", "WEB_PORT", "SITE_ADDRESS",
}
stand_env = {
    key: value for key, value in os.environ.items()
    if not key.startswith("COMPOSE_") and key not in overrides
}
release_sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
with tempfile.TemporaryDirectory(prefix="koren-ui-check-") as directory:
    env_file = Path(directory) / "stand.env"
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    env_file.write_text(
        "DB_PASSWORD=" + secrets.token_urlsafe(32)
        + "\nMAX_BOT_TOKEN=" + secrets.token_urlsafe(32)
        + "\nMAX_WEBHOOK_SECRET=" + secrets.token_urlsafe(32)
        + "\nMAX_WORKERS_ENABLED=false\nRELEASE_SHA=" + release_sha
        + f"\nSESSION_COOKIE_SECURE=false\nWEB_PORT={port}\n",
        encoding="utf-8",
    )
    env_file.chmod(0o600)
    compose = ["docker", "compose", "--env-file", str(env_file), "-p", project, "-f", "compose.release.yaml"]
    try:
        print(f"Synthetic stand: {project}, loopback port {port}", flush=True)
        subprocess.run(compose + ["up", "--build", "--wait", "--wait-timeout", "180"], cwd=ROOT, env=stand_env, check=True)
        url = f"http://127.0.0.1:{port}"
        for attempt in range(60):
            try:
                with urllib.request.urlopen(url + "/api/v1/csrf", timeout=3) as response:
                    if response.status == 200:
                        break
            except (urllib.error.URLError, TimeoutError):
                time.sleep(1)
        else:
            raise SystemExit("Backend did not become ready; no integration PASS claimed")
        env = {**stand_env, "KOREN_E2E_ENV": str(env_file), "KOREN_E2E_PROJECT": project, "KOREN_E2E_URL": url}
        subprocess.run(["npm", "run", "test:integration"], cwd=ROOT / "frontend", env=env, check=True)
        print(f"Build, startup and real HTTP browser checks passed in {time.monotonic() - started:.1f}s", flush=True)
    finally:
        # These are only the uniquely named disposable fixtures created above, never an existing stand.
        subprocess.run(compose + ["down", "--volumes", "--remove-orphans"], cwd=ROOT, env=stand_env, check=False)
