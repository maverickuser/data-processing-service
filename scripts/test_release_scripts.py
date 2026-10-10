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
    calls.write(" ".join(args) + "\\n")
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
    Path(args[-1]).write_text(json.dumps("applied migrations=1 schemaVersion=6"), encoding="utf-8")
    if os.environ.get("MIGRATION_FAIL") == "1":
        print('{"StatusCode":200,"FunctionError":"Unhandled"}')
    else:
        print('{"StatusCode":200}')
elif args[:2] == ["lambda", "get-alias"]:
    print('{"FunctionVersion":"1","RevisionId":"revision-one"}')
elif args[:2] == ["lambda", "update-alias"]:
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
        for refusal in ("NO_ENDPOINT", "NO_PRIVATE_DNS", "NAT_ENABLED"):
            with self.subTest(refusal=refusal):
                self.environment[refusal] = "1"
                self.assertNotEqual(self.run_script("check_network_endpoint.sh").returncode, 0)
                self.environment.pop(refusal)

    def test_failed_migration_never_moves_an_alias(self):
        outputs = Path(self.directory.name) / "outputs.json"
        outputs.write_text(
            json.dumps(
                {
                    "alias_name": {"value": "live"},
                    "function_names": {
                        "value": {"migration": "processor-migration", "worker": "processor-worker"}
                    },
                    "published_versions": {"value": {"migration": "2", "worker": "3"}},
                }
            ),
            encoding="utf-8",
        )
        self.environment["MIGRATION_FAIL"] = "1"
        self.assertNotEqual(self.run_script("migrate_and_promote.sh", outputs).returncode, 0)
        self.assertNotIn("update-alias", self.calls.read_text(encoding="utf-8"))
        self.environment.pop("MIGRATION_FAIL")
        self.assertEqual(self.run_script("migrate_and_promote.sh", outputs).returncode, 0)
        self.assertEqual(self.calls.read_text(encoding="utf-8").count("update-alias"), 2)


if __name__ == "__main__":
    unittest.main()
