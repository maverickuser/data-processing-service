"""Check the smoke script's verdicts against a fake curl, without network access."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parent
FAKE_CURL = """#!/usr/bin/env python3
import os
from pathlib import Path
import sys

args = sys.argv[1:]
url = args[-1]
with open(os.environ["CURL_CALLS"], "a", encoding="utf-8") as calls:
    calls.write(" ".join(args) + "\\n")
if os.environ.get("UNREACHABLE"):
    sys.stdout.write("000 ")
    raise SystemExit(7)
count_file = Path(os.environ["CURL_COUNT"])
count = int(count_file.read_text()) + 1 if count_file.exists() else 1
count_file.write_text(str(count))
if url.endswith("/v1/securities/INE000000Z06"):
    if count <= int(os.environ.get("READ_ERRORS_FIRST", "0")):
        status, content_type, body = "503", "application/problem+json", '{"status":503}'
    else:
        status = os.environ.get("READ_STATUS", "404")
        content_type = os.environ.get("READ_TYPE", "application/problem+json")
        body = os.environ.get("READ_BODY", '{"status":404,"code":"NOT_FOUND"}')
elif url.endswith("/v1/event-ingestions") and "POST" in args:
    status, content_type, body = os.environ.get("SUBMIT_STATUS", "403"), "application/json", "{}"
else:
    raise SystemExit("Unexpected request: " + " ".join(args))
Path(args[args.index("--output") + 1]).write_text(body, encoding="utf-8")
sys.stdout.write(f"{status} {content_type}")
"""


class SmokeScriptTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        root = Path(self.directory.name)
        fake = root / "curl"
        fake.write_text(FAKE_CURL, encoding="utf-8")
        fake.chmod(0o755)
        self.calls = root / "calls"
        self.environment = os.environ.copy()
        self.environment.update(
            PATH=f"{root}:{os.environ['PATH']}",
            CURL_CALLS=str(self.calls),
            CURL_COUNT=str(root / "count"),
            SMOKE_ATTEMPTS="3",
            SMOKE_RETRY_DELAY_SECONDS="0",
        )

    def run_smoke(self):
        return subprocess.run(
            ["bash", str(ROOT / "smoke.sh"), "https://processing.example/"],
            env=self.environment,
            capture_output=True,
            text=True,
            check=False,
        )

    def test_both_cases_pass_against_a_healthy_service(self):
        result = self.run_smoke()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("S-01 passed", result.stdout)
        self.assertIn("S-02 passed", result.stdout)
        calls = self.calls.read_text(encoding="utf-8")
        self.assertIn("https://processing.example/v1/securities/INE000000Z06", calls)
        self.assertNotIn("Authorization", calls)

    def test_read_failures_fail_s01_only(self):
        for name, value in (
            ("READ_STATUS", "500"),
            ("READ_STATUS", "200"),
            ("READ_TYPE", "text/html"),
            ("READ_BODY", '{"status":404,"code":"OTHER"}'),
        ):
            with self.subTest(**{name: value}):
                self.environment[name] = value
                result = self.run_smoke()
                self.assertEqual(result.returncode, 1)
                self.assertIn("S-01 failed", result.stdout)
                self.assertIn("S-02 passed", result.stdout)
                self.environment.pop(name)

    def test_accepted_submission_fails_s02(self):
        self.environment["SUBMIT_STATUS"] = "202"
        result = self.run_smoke()
        self.assertEqual(result.returncode, 1)
        self.assertIn("S-02 failed", result.stdout)

    def test_a_slow_first_connection_is_retried(self):
        self.environment["READ_ERRORS_FIRST"] = "2"
        result = self.run_smoke()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.calls.read_text(encoding="utf-8").count("INE000000Z06"), 3)

    def test_an_unreachable_service_fails_both_cases_after_retries(self):
        self.environment["UNREACHABLE"] = "1"
        result = self.run_smoke()
        self.assertEqual(result.returncode, 2)
        self.assertEqual(self.calls.read_text(encoding="utf-8").count("\n"), 6)


if __name__ == "__main__":
    unittest.main()
