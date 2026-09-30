"""Network-free tests of runner control flow. These do not prove any product API behavior."""

import contextlib
import importlib.util
import io
import json
from http.cookiejar import Cookie
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
from urllib.error import URLError

import check_data_api as runner


SECRET = "private-value-must-never-be-printed"
SNAPSHOT = {"head": "a" * 40, "dirty": True, "sha256": "b" * 64, "files": []}


class RunnerControlTests(unittest.TestCase):
    def exercise(self, *, setup_error=None, scenario_error=None, up_error=None,
                 logs_error=None, down_error=None, down_code=0, logs="", sensitive=(),
                 snapshots=None, constructor_error=None):
        stand = Mock()
        stand.sensitive = set(sensitive)
        stand.up.side_effect = up_error
        stand.logs.side_effect = logs_error
        stand.logs.return_value = logs
        stand.down.side_effect = down_error
        stand.down.return_value = down_code

        def public_checks(_stand, protocol):
            # Reproduce the original false-PASS path: four checks before a login aborts.
            for index in range(4):
                protocol.check("public", f"public-{index}", True, 200, 200)

        scenario = Mock(side_effect=scenario_error) if scenario_error else Mock(
            side_effect=lambda number, *_args: f"case-{number}"
        )
        output = io.StringIO()
        with tempfile.TemporaryDirectory(prefix="dapi-runner-unit-") as directory:
            report_path = Path(directory) / "protocol.json"
            with contextlib.ExitStack() as stack:
                stack.enter_context(patch.object(runner, "Stand", return_value=stand, side_effect=constructor_error))
                stack.enter_context(patch.object(runner, "source_snapshot", side_effect=snapshots or [SNAPSHOT, SNAPSHOT]))
                stack.enter_context(patch.object(runner, "public_checks", side_effect=public_checks))
                stack.enter_context(patch.object(runner, "prepare_roles", side_effect=setup_error,
                                                return_value=({}, {}, "house-a", [])))
                stack.enter_context(patch.object(runner, "scenario", scenario))
                stack.enter_context(contextlib.redirect_stdout(output))
                result = runner.main(["--runs", "2", "--report", str(report_path)])
            self.assertTrue(report_path.exists(), "every execution failure must leave JSON evidence")
            raw = report_path.read_text(encoding="utf-8")
            report = json.loads(raw)
        self.assertNotIn(SECRET, output.getvalue() + raw)
        self.assertEqual(report["failed"], sum(row["status"] == "FAIL" for row in report["checks"]))
        return result, report, stand

    def assert_failure(self, report, check):
        self.assertEqual(report["result"], "FAIL")
        self.assertTrue(any(row["check"] == check and row["status"] == "FAIL" for row in report["checks"]))

    def test_success_requires_completed_runs_cleanup_and_stable_sources(self):
        result, report, stand = self.exercise()
        self.assertEqual((result, report["result"], report["failed"]), (0, "PASS", 0))
        self.assertEqual(report["sourceBefore"], report["sourceAfter"])
        stand.logs.assert_called_once()
        stand.down.assert_called_once()

    def test_login_checkfailed_cannot_turn_public_passes_into_success(self):
        result, report, stand = self.exercise(setup_error=runner.CheckFailed(SECRET))
        self.assertEqual(result, 1)
        self.assert_failure(report, "setup-aborted")
        self.assert_failure(report, "runs-completed-with-distinct-cases")
        stand.down.assert_called_once()

    def test_unexpected_setup_and_psql_errors_leave_failed_json(self):
        for failure in (URLError(SECRET), KeyError(SECRET), SystemExit(SECRET)):
            with self.subTest(type=type(failure).__name__):
                result, report, stand = self.exercise(setup_error=failure)
                self.assertEqual(result, 1)
                self.assert_failure(report, "setup-aborted")
                stand.down.assert_called_once()

    def test_up_calledprocesserror_still_reads_logs_and_cleans_up(self):
        result, report, stand = self.exercise(up_error=subprocess.CalledProcessError(1, SECRET, stderr=SECRET))
        self.assertEqual(result, 1)
        self.assert_failure(report, "setup-aborted")
        stand.logs.assert_called_once()
        stand.down.assert_called_once()

    def test_constructor_failure_also_leaves_json(self):
        result, report, stand = self.exercise(constructor_error=OSError(SECRET))
        self.assertEqual(result, 1)
        self.assert_failure(report, "setup-aborted")
        stand.down.assert_not_called()

    def test_runtime_exceptions_and_bare_checkfailed_are_failures(self):
        for failure in (runner.CheckFailed(SECRET), URLError(SECRET), KeyError(SECRET), SystemExit(SECRET)):
            with self.subTest(type=type(failure).__name__):
                result, report, stand = self.exercise(scenario_error=failure)
                self.assertEqual(result, 1)
                self.assert_failure(report, "scenario-aborted" if isinstance(failure, runner.CheckFailed) else "runtime-aborted")
                stand.down.assert_called_once()

    def test_cleanup_nonzero_invalidates_other_passes(self):
        result, report, _stand = self.exercise(down_code=1)
        self.assertEqual(result, 1)
        self.assert_failure(report, "cleanup")

    def test_cleanup_exception_does_not_lose_json(self):
        result, report, _stand = self.exercise(down_error=OSError(SECRET))
        self.assertEqual(result, 1)
        self.assert_failure(report, "cleanup")

    def test_log_read_error_invalidates_pass_and_still_cleans_up(self):
        result, report, stand = self.exercise(logs_error=runner.CheckFailed(SECRET))
        self.assertEqual(result, 1)
        self.assert_failure(report, "log-read")
        stand.down.assert_called_once()

    def test_secret_and_text_leaks_are_counted_without_printing_values(self):
        for logs, values in ((SECRET, (SECRET,)), (runner.REPORT_TEXT, ())):
            with self.subTest(secret=bool(values)):
                result, report, _stand = self.exercise(logs=logs, sensitive=values)
                self.assertEqual(result, 1)
                self.assert_failure(report, "no-secrets-or-text-in-logs")

    def test_changed_working_tree_or_head_invalidates_pass(self):
        for field in ("sha256", "head"):
            with self.subTest(field=field):
                changed = {**SNAPSHOT, field: "changed"}
                result, report, _stand = self.exercise(snapshots=[SNAPSHOT, changed])
                self.assertEqual(result, 1)
                self.assert_failure(report, "source-unchanged")

    def test_snapshot_failure_preserves_failed_json(self):
        for snapshots in ([OSError(SECRET), SNAPSHOT], [SNAPSHOT, OSError(SECRET)]):
            result, report, _stand = self.exercise(snapshots=snapshots)
            self.assertEqual(result, 1)
            self.assertEqual(report["result"], "FAIL")

    def test_import_does_not_parse_argv_or_start_stand(self):
        spec = importlib.util.spec_from_file_location("dapi_import_probe", runner.__file__)
        module = importlib.util.module_from_spec(spec)
        with patch.object(sys, "argv", ["probe", "--unknown-argument"]), patch.object(
            subprocess, "run", side_effect=AssertionError("import started a process")
        ):
            spec.loader.exec_module(module)
        self.assertTrue(callable(module.main))


class StandControlTests(unittest.TestCase):
    def stand_without_resources(self):
        stand = runner.Stand.__new__(runner.Stand)
        stand.compose = ["docker", "compose"]
        stand.project = runner.PROJECT_PREFIX + "unit"
        stand.env = {}
        stand.directory = Mock()
        return stand

    def test_nonzero_logs_are_not_treated_as_empty_clean_log(self):
        stand = self.stand_without_resources()
        with patch.object(subprocess, "run", return_value=SimpleNamespace(returncode=1, stdout="", stderr=SECRET)):
            with self.assertRaises(runner.CheckFailed) as caught:
                stand.logs()
        self.assertNotIn(SECRET, str(caught.exception))

    def test_down_returns_nonzero_and_removes_private_env(self):
        stand = self.stand_without_resources()
        with patch.object(subprocess, "run", return_value=SimpleNamespace(returncode=1)):
            self.assertEqual(stand.down(), 1)
        stand.directory.cleanup.assert_called_once()

    def test_down_exception_still_removes_private_env(self):
        stand = self.stand_without_resources()
        with patch.object(subprocess, "run", side_effect=OSError(SECRET)):
            with self.assertRaises(OSError):
                stand.down()
        stand.directory.cleanup.assert_called_once()


class ClientSecretTests(unittest.TestCase):
    def test_keeps_all_initdata_csrf_and_cookie_generations(self):
        sensitive = set()
        client = runner.Client("http://127.0.0.1:1", "unit", sensitive)
        sent_init_data = []
        responses = [
            ({"headerName": "X-CSRF-TOKEN", "token": "csrf-before-login"}, "cookie-before-login"),
            ({"userId": "unit"}, "cookie-after-login"),
            ({"headerName": "X-CSRF-TOKEN", "token": "csrf-after-login"}, "cookie-after-refresh"),
        ]

        def respond(request, **_kwargs):
            if request.data:
                sent_init_data.append(json.loads(request.data)["initData"])
            body, cookie = responses.pop(0)
            client.jar.set_cookie(Cookie(0, "SESSION", cookie, None, False, "127.0.0.1", False, False,
                                         "/", True, False, None, True, None, None, {}, False))
            response = Mock(status=200, headers={})
            response.read.return_value = json.dumps(body).encode()
            response.__enter__ = Mock(return_value=response)
            response.__exit__ = Mock(return_value=False)
            return response

        with patch.object(client.opener, "open", side_effect=respond):
            client.login("unit-test-signing-token", 123)
        self.assertEqual(len(sent_init_data), 1)
        self.assertTrue(set(sent_init_data) <= sensitive)
        self.assertTrue({"csrf-before-login", "csrf-after-login", "cookie-before-login",
                         "cookie-after-login", "cookie-after-refresh"} <= sensitive)


if __name__ == "__main__":
    unittest.main()
