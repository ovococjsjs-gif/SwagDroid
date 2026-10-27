"""Tests of CI result gating, not substitutes for the Android/JUnit suites themselves."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "summarize-junit.py"


class SummaryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.addCleanup(self.tmp.cleanup)

    def report(self, module, *, kind="android", tests=4, failures=0, skipped=0, body=""):
        relative = "build/outputs/androidTest-results/connected/debug/emulator/TEST-fixture.xml" if kind == "android" else "build/test-results/testDebugUnitTest/TEST-fixture.xml"
        path = self.root / module / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(f'<testsuite tests="{tests}" failures="{failures}" errors="0" skipped="{skipped}">{body}</testsuite>')
        return path

    def run_summary(self, *extra):
        return subprocess.run(["python3", str(SCRIPT), "--root", str(self.root), *extra], capture_output=True, text=True)

    def android(self, *extra):
        return self.run_summary("--kind", "android", "--min-tests", "8", "--require-module", "app", "--require-module", "core:vault", "--fail-on-skip", *extra)

    def test_unit_reports_are_not_android_coverage(self):
        self.report("core/vault", kind="unit", tests=156)
        self.assertNotEqual(0, self.android().returncode)
        self.assertEqual(0, self.run_summary().returncode)

    def test_requires_both_modules_and_minimum_count(self):
        self.report("app", tests=8)
        self.assertNotEqual(0, self.android().returncode)
        self.report("app", tests=3)
        self.report("core/vault", tests=4)
        self.assertNotEqual(0, self.android().returncode)

    def test_success_is_summarized_separately(self):
        self.report("app")
        self.report("core/vault")
        result = self.android()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        summary = json.loads((self.root / "build/ci-android-validation-summary.json").read_text())
        self.assertEqual("android", summary["kind"])
        self.assertEqual(8, summary["totals"]["tests"])
        self.assertEqual(0, summary["totals"]["skipped"])
        self.assertEqual([], summary["missingModules"])
        self.assertIn("Android instrumentation totals", result.stdout)

    def test_required_smoke_cases_cannot_be_skipped(self):
        self.report("app", skipped=1)
        self.report("core/vault")
        self.assertNotEqual(0, self.android().returncode)

    def test_failure_details_remain_available(self):
        self.report("app", failures=1, body='<testcase classname="Smoke" name="case"><failure message="fixture failure">stack evidence</failure></testcase>')
        self.report("core/vault")
        result = self.android()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("stack evidence", result.stdout)

    def test_empty_or_malformed_reports_are_not_success(self):
        self.assertNotEqual(0, self.run_summary("--kind", "android").returncode)
        self.assertEqual(0, self.run_summary("--kind", "android", "--allow-empty").returncode)
        self.report("app").write_text("not XML")
        self.assertNotEqual(0, self.run_summary("--kind", "android", "--allow-empty").returncode)


if __name__ == "__main__":
    unittest.main()
