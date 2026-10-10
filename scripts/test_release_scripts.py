"""Check the release's refusal paths without AWS credentials or network access."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parent
FAKE_AWS = """#!/usr/bin/env python3
import json
import os
from pathlib import Path
import sys

args = sys.argv[1:]
with open(os.environ["AWS_CALLS"], "a", encoding="utf-8") as calls:
    calls.write(" ".join(args) + " AWS_MAX_ATTEMPTS=" + os.environ.get("AWS_MAX_ATTEMPTS", "unset") + "\\n")
if args[:2] == ["s3api", "get-object"]:
    state = {
        "outputs": {
            "vpc_id": {"value": "vpc-test"},
            "interface_endpoint_ids": {"value": {} if os.environ.get("NO_ENDPOINT") else {"secretsmanager": "vpce-secret"}},
            "nat_gateway_ids_by_az": {"value": {"az-a": "nat-test"} if os.environ.get("NAT_ENABLED") else {}},
        }
    }
    Path(args[-1]).write_text(json.dumps(state), encoding="utf-8")
    print("{}")
elif args[:2] == ["ec2", "describe-vpc-endpoints"]:
    print(json.dumps({"VpcEndpoints": [{
        "VpcEndpointId": "vpce-secret", "VpcId": "vpc-test",
        "VpcEndpointType": "Interface",
        "ServiceName": "com.amazonaws.ap-south-1.secretsmanager",
        "PrivateDnsEnabled": not bool(os.environ.get("NO_PRIVATE_DNS")),
        "State": os.environ.get("ENDPOINT_STATE", "available"),
    }]}))
elif args[:2] == ["lambda", "invoke"]:
    if os.environ.get("INVOKE_EXIT"):
        raise SystemExit(255)
    payload = os.environ.get("MIGRATION_PAYLOAD", "applied migrations=1 schemaVersion=6")
    Path(args[-1]).write_text(json.dumps(payload), encoding="utf-8")
    if os.environ.get("MIGRATION_FAIL") == "1":
        print('{"StatusCode":200,"FunctionError":"Unhandled"}')
    else:
        print('{"StatusCode":200}')
elif args[:2] == ["lambda", "get-alias"]:
    function = args[args.index("--function-name") + 1]
    aliases = Path(os.environ["AWS_ALIASES"])
    moved = json.loads(aliases.read_text(encoding="utf-8")) if aliases.exists() else {}
    alias = {"FunctionVersion": moved.get(function, "1"), "RevisionId": "revision-one"}
    if os.environ.get("WEIGHTED"):
        alias["RoutingConfig"] = {"AdditionalVersionWeights": {"2": 0.1}}
    print(json.dumps(alias))
elif args[:2] == ["lambda", "update-alias"]:
    function = args[args.index("--function-name") + 1]
    if os.environ.get("UPDATE_FAIL_FOR") == function:
        raise SystemExit(254)
    aliases = Path(os.environ["AWS_ALIASES"])
    moved = json.loads(aliases.read_text(encoding="utf-8")) if aliases.exists() else {}
    moved[function] = args[args.index("--function-version") + 1]
    aliases.write_text(json.dumps(moved), encoding="utf-8")
    print("{}")
else:
    raise SystemExit("Unexpected AWS call: " + " ".join(args))
"""


class ReleaseScriptsTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        root = Path(self.directory.name)
        fake = root / "aws"
        fake.write_text(FAKE_AWS, encoding="utf-8")
        fake.chmod(0o755)
        self.calls = root / "calls"
        self.environment = os.environ.copy()
        self.environment.update(
            PATH=f"{root}:{os.environ['PATH']}",
            AWS_CALLS=str(self.calls),
            AWS_ALIASES=str(root / "aliases.json"),
            AWS_REGION="ap-south-1",
        )

    def run_script(self, name, *args):
        return subprocess.run(
            ["bash", str(ROOT / name), *map(str, args)],
            env=self.environment,
            capture_output=True,
            text=True,
            check=False,
        )

    def test_network_requires_available_endpoint(self):
        self.assertEqual(self.run_script("check_network_endpoint.sh").returncode, 0)
        self.environment["ENDPOINT_STATE"] = "pending"
        self.assertNotEqual(self.run_script("check_network_endpoint.sh").returncode, 0)
        self.environment.pop("ENDPOINT_STATE")
        for refusal in ("NO_ENDPOINT", "NO_PRIVATE_DNS"):
            with self.subTest(refusal=refusal):
                self.environment[refusal] = "1"
                self.assertNotEqual(self.run_script("check_network_endpoint.sh").returncode, 0)
                self.environment.pop(refusal)

    def test_network_with_nat_only_warns(self):
        self.environment["NAT_ENABLED"] = "1"
        result = self.run_script("check_network_endpoint.sh")
        self.assertEqual(result.returncode, 0)
        self.assertIn("::warning::", result.stdout)

    def write_outputs(self, versions):
        outputs = Path(self.directory.name) / "outputs.json"
        outputs.write_text(
            json.dumps(
                {
                    "alias_name": {"value": "live"},
                    "function_names": {"value": {name: f"processor-{name}" for name in versions}},
                    "published_versions": {"value": versions},
                }
            ),
            encoding="utf-8",
        )
        return outputs

    def test_failed_migration_never_moves_an_alias(self):
        outputs = self.write_outputs({"migration": "2", "worker": "3"})
        self.environment["MIGRATION_FAIL"] = "1"
        self.assertNotEqual(self.run_script("migrate_and_promote.sh", outputs).returncode, 0)
        self.assertNotIn("update-alias", self.calls.read_text(encoding="utf-8"))
        self.environment.pop("MIGRATION_FAIL")
        self.assertEqual(self.run_script("migrate_and_promote.sh", outputs).returncode, 0)
        self.assertEqual(self.calls.read_text(encoding="utf-8").count("update-alias"), 2)

    def test_migration_refusals_never_move_an_alias(self):
        outputs = self.write_outputs({"migration": "2", "worker": "3"})
        for name, value in (("INVOKE_EXIT", "1"), ("MIGRATION_PAYLOAD", "something else")):
            with self.subTest(refusal=name):
                self.environment[name] = value
                self.assertNotEqual(self.run_script("migrate_and_promote.sh", outputs).returncode, 0)
                self.assertNotIn("update-alias", self.calls.read_text(encoding="utf-8"))
                self.environment.pop(name)

    def test_weighted_alias_is_refused(self):
        outputs = self.write_outputs({"migration": "2", "worker": "3"})
        self.environment["WEIGHTED"] = "1"
        self.assertNotEqual(self.run_script("migrate_and_promote.sh", outputs).returncode, 0)
        self.assertNotIn("update-alias", self.calls.read_text(encoding="utf-8"))

    def test_failed_promotion_stops_and_a_rerun_completes_it(self):
        outputs = self.write_outputs({"migration": "2", "read": "4", "worker": "3"})
        self.environment["UPDATE_FAIL_FOR"] = "processor-read"
        self.assertNotEqual(self.run_script("migrate_and_promote.sh", outputs).returncode, 0)
        self.assertNotIn("processor-worker --name live --function-version", self.calls.read_text(encoding="utf-8"))
        self.environment.pop("UPDATE_FAIL_FOR")
        self.calls.write_text("", encoding="utf-8")
        result = self.run_script("migrate_and_promote.sh", outputs)
        self.assertEqual(result.returncode, 0)
        self.assertIn("migration already points to version 2.", result.stdout)
        self.assertEqual(self.calls.read_text(encoding="utf-8").count("update-alias"), 2)

    def test_migration_waits_past_the_function_timeout_without_retrying(self):
        outputs = self.write_outputs({"migration": "2"})
        self.assertEqual(self.run_script("migrate_and_promote.sh", outputs).returncode, 0)
        invoke = next(
            line
            for line in self.calls.read_text(encoding="utf-8").splitlines()
            if line.startswith("lambda invoke")
        )
        self.assertIn("--cli-read-timeout 360", invoke)
        self.assertIn("AWS_MAX_ATTEMPTS=1", invoke)


if __name__ == "__main__":
    unittest.main()
