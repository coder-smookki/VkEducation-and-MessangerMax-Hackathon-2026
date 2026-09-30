"""Test the Mini App on disposable Java/PostgreSQL, optionally with real RULES.

Run after `npm --prefix frontend ci` and `npx --prefix frontend playwright install chromium`.
Only synthetic data and random test-only secrets are used. Results survive in a private
temporary directory; the unique Compose project, volumes and secret env file do not.
This does not exercise a real MAX client, send messages, or contact a production stand.
"""

import argparse
from datetime import datetime, timezone
import hashlib
import json
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


def source_snapshot():
    """Hash working-tree bytes, including dirty/untracked sources, without copying them."""
    git_checkout = (ROOT / ".git").exists()
    if git_checkout:
        names = subprocess.check_output(
            ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=ROOT,
        ).decode().split("\0")
    else:
        excluded = {"node_modules", ".venv", ".git", "target", "dist", "__pycache__",
                    ".pytest_cache", ".ruff_cache", "test-results", "playwright-report", "output", "tmp"}
        names = [
            str(path.relative_to(ROOT)) for path in ROOT.rglob("*")
            if path.is_file() and not excluded.intersection(path.relative_to(ROOT).parts)
            and path.name != ".env" and ".private." not in path.name
            and not path.name.endswith(".log") and path.name != ".DS_Store"
        ]
    files = []
    for name in sorted(set(names) - {""}):
        path = ROOT / name
        entry = {"path": name}
        if path.is_symlink():
            entry["symlink"] = os.readlink(path)
        elif path.is_file():
            entry["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
            entry["executable"] = bool(path.stat().st_mode & 0o111)
        else:
            entry["missing"] = True
        files.append(entry)
    encoded = json.dumps(files, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    digest = hashlib.sha256(encoded).hexdigest()
    return {
        "head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
                if git_checkout else "archive-" + digest[:16],
        "dirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT)) if git_checkout else False,
        "sha256": digest,
        "scope": "source files, executable bits and symlinks; build output and private environment excluded",
        "files": files,
    }


def save_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--analysis", action="store_true",
        help="start the analysis Compose profile and test Python RULES through Java and UI",
    )
    args = parser.parse_args()
    project = "koren-ui-check-" + secrets.token_hex(4)
    started = time.monotonic()
    artifacts = Path(tempfile.mkdtemp(prefix="koren-ui-results-"))
    before = source_snapshot()
    save_json(artifacts / "source-before.json", before)
    result = {
        "project": project,
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "analysisEnabled": args.analysis,
        "releaseSha": before["head"],
        "sourceDigest": before["sha256"],
        "dirty": before["dirty"],
        "result": "FAILED",
        "checks": {},
        "limitations": "Synthetic signed MAX launch; Chromium; no real MAX, messages, physical devices or production changes. Build may use local caches.",
    }
    # Parent-shell settings must not activate HTTPS or replace disposable secrets/config.
    overrides = {
        "DB_PASSWORD", "MAX_BOT_TOKEN", "MAX_WEBHOOK_SECRET", "MAX_WORKERS_ENABLED",
        "RELEASE_SHA", "ANALYSIS_ENABLED", "TRAINING_MODE", "CASE_NUMBER_PREFIX",
        "SESSION_COOKIE_SECURE", "SESSION_COOKIE_SAMESITE", "WEB_PORT", "SITE_ADDRESS",
        "REVIEW_INVITATIONS_ENABLED",
    }
    stand_env = {
        key: value for key, value in os.environ.items()
        if not key.startswith(("COMPOSE_", "KOREN_E2E_")) and key not in overrides
    }
    with tempfile.TemporaryDirectory(prefix="koren-ui-check-") as directory:
        env_file = Path(directory) / "stand.env"
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            port = listener.getsockname()[1]
        env_file.write_text(
            "DB_PASSWORD=" + secrets.token_urlsafe(32)
            + "\nMAX_BOT_TOKEN=" + secrets.token_urlsafe(32)
            + "\nMAX_WEBHOOK_SECRET=" + secrets.token_urlsafe(32)
            + "\nMAX_WORKERS_ENABLED=false\nRELEASE_SHA=" + before["head"]
            + f"\nANALYSIS_ENABLED={str(args.analysis).lower()}"
            + "\nTRAINING_MODE=true\nCASE_NUMBER_PREFIX=ДЕМО-"
            + "\nREVIEW_INVITATIONS_ENABLED=true"
            + f"\nSESSION_COOKIE_SECURE=false\nSESSION_COOKIE_SAMESITE=Lax\nWEB_PORT={port}\n",
            encoding="utf-8",
        )
        env_file.chmod(0o600)
        compose = ["docker", "compose", "--env-file", str(env_file), "-p", project, "-f", "compose.release.yaml"]
        if args.analysis:
            compose += ["--profile", "analysis"]
        result["url"] = url = f"http://127.0.0.1:{port}"
        print(f"Synthetic stand: {project}, {url}, analysis={args.analysis}", flush=True)
        print(f"Private results: {artifacts}", flush=True)
        try:
            subprocess.run(compose + ["up", "--build", "--wait", "--wait-timeout", "180"], cwd=ROOT, env=stand_env, check=True)
            result["checks"]["buildAndStart"] = "PASS"
            result["buildAndStartSeconds"] = round(time.monotonic() - started, 1)
            for attempt in range(60):
                try:
                    with urllib.request.urlopen(url + "/api/v1/csrf", timeout=3) as response:
                        if response.status == 200:
                            break
                except (urllib.error.URLError, TimeoutError):
                    time.sleep(1)
            else:
                raise RuntimeError("Backend did not become ready; no integration PASS claimed")
            env = {
                **stand_env,
                "KOREN_E2E_ENV": str(env_file), "KOREN_E2E_PROJECT": project, "KOREN_E2E_URL": url,
                "KOREN_E2E_ANALYSIS": str(args.analysis).lower(),
                "KOREN_QA_CAPTURE": str(artifacts / "screens"),
                "PLAYWRIGHT_JSON_OUTPUT_FILE": str(artifacts / "playwright.json"),
            }
            subprocess.run(
                ["npm", "run", "test:integration", "--", "--reporter=list,json", "--output", str(artifacts / "test-results")],
                cwd=ROOT / "frontend", env=env, check=True,
            )
            result["checks"]["integration"] = "PASS"
            result["result"] = "PASS"
        except (OSError, subprocess.CalledProcessError, RuntimeError) as error:
            # Do not persist command/env contents; browser JSON carries individual failures.
            result["failureType"] = type(error).__name__
            print(f"Local playground failed: {type(error).__name__}; see results and command output", flush=True)
        except KeyboardInterrupt:
            result["result"] = "INTERRUPTED"
        finally:
            # Only the uniquely named disposable fixtures above, never another stand.
            try:
                cleanup = subprocess.run(compose + ["down", "--volumes", "--remove-orphans"], cwd=ROOT, env=stand_env, check=False)
                result["checks"]["cleanup"] = "PASS" if cleanup.returncode == 0 else "FAIL"
            except OSError:
                result["checks"]["cleanup"] = "FAIL"
            after = source_snapshot()
            save_json(artifacts / "source-after.json", after)
            result["sourceConsistent"] = before["sha256"] == after["sha256"]
            if result["result"] == "PASS" and not result["sourceConsistent"]:
                result["result"] = "INCONCLUSIVE_SOURCE_CHANGED"
            if result["checks"]["cleanup"] != "PASS":
                result["result"] = "FAILED_CLEANUP"
            result["elapsedSeconds"] = round(time.monotonic() - started, 1)
            save_json(artifacts / "result.json", result)
    print(f"{result['result']}: {artifacts / 'result.json'} ({result['elapsedSeconds']}s)", flush=True)
    return 0 if result["result"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
