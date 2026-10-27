#!/usr/bin/env python3
"""Summarize real Gradle JUnit XML in CI annotations and the GitHub job summary.

This can be invoked by the existing workflow without needing workflow-edit permissions.
No reports is NOT interpreted as passing tests. Generated output stays under ignored build/.
"""
import argparse
import json
import os
from collections import defaultdict
from pathlib import Path
import xml.etree.ElementTree as ET


def escape(value):
    return str(value).replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def annotation(level, title, message):
    print(f"::{level} title={escape(title)}::{escape(message)}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--allow-empty", action="store_true", help="Only for a build step that already failed")
    parser.add_argument("--kind", choices=("unit", "android"), default="unit")
    parser.add_argument("--min-tests", type=int, default=1)
    parser.add_argument("--require-module", action="append", default=[])
    parser.add_argument("--fail-on-skip", action="store_true")
    args = parser.parse_args()
    root = args.root.resolve()
    pattern = "**/build/test-results/testDebugUnitTest/TEST-*.xml" if args.kind == "unit" else "**/build/outputs/androidTest-results/connected/**/TEST-*.xml"
    files = sorted(root.glob(pattern))
    label = "JUnit" if args.kind == "unit" else "Android instrumentation"
    modules = defaultdict(lambda: dict(tests=0, failures=0, errors=0, skipped=0))
    malformed = []
    failed_cases = []
    for path in files:
        relative = path.relative_to(root)
        module = ":" + ":".join(relative.parts[:relative.parts.index("build")])
        try:
            document = ET.parse(path).getroot()
            suites = [document] if document.tag == "testsuite" else list(document.findall("testsuite"))
            if not suites:
                raise ValueError("No testsuites in report")
            for suite in suites:
                stats = {key: int(suite.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}
                if min(stats.values()) < 0 or stats["tests"] < sum(stats[key] for key in ("failures", "errors", "skipped")):
                    raise ValueError("Invalid test counts")
                for key, count in stats.items():
                    modules[module][key] += count
                for case in suite.findall("testcase"):
                    problem = case.find("failure")
                    if problem is None:
                        problem = case.find("error")
                    if problem is not None:
                        failed_cases.append({"module": module, "class": case.get("classname", ""), "name": case.get("name", ""), "message": problem.get("message", (problem.text or "")[:3000])[:3000], "details": (problem.text or "")[:4000] if args.kind == "android" else ""})
        except (ET.ParseError, ValueError, OSError) as error:
            malformed.append(f"{relative}: {error}")

    totals = {key: sum(stats[key] for stats in modules.values()) for key in ("tests", "failures", "errors", "skipped")}
    required = {":" + module.strip(":") for module in args.require_module}
    missing_modules = sorted(required.difference(modules))
    report = {"kind": args.kind, "missingModules": missing_modules, "headSha": os.environ.get("GITHUB_SHA"), "xmlFiles": len(files), "totals": totals, "modules": dict(sorted(modules.items())), "malformed": malformed, "failedCases": failed_cases}
    output = root / ("build/ci-validation-summary.json" if args.kind == "unit" else "build/ci-android-validation-summary.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    if not files:
        annotation("warning" if args.allow_empty else "error", label + " unavailable", "No JUnit XML found; tests have not been confirmed.")
    else:
        annotation("notice", label + " totals", json.dumps({"xmlFiles": len(files), **totals}, sort_keys=True))
    for module, stats in sorted(modules.items()):
        annotation("notice", label + " " + module, json.dumps(stats, sort_keys=True))
    for error in malformed:
        annotation("error", label + " report unreadable", error)
    for failure in failed_cases[:15]:
        annotation("error", label + " failed test", json.dumps(failure, ensure_ascii=False))

    if missing_modules:
        annotation("error", label + " missing modules", ", ".join(missing_modules))
    if files and totals["tests"] < args.min_tests:
        annotation("error", label + " incomplete run", f"Expected at least {args.min_tests} tests, found {totals['tests']}")
    if args.fail_on_skip and totals["skipped"]:
        annotation("error", label + " skipped tests", "Required smoke cases were skipped")

    summary = ["## " + label + " results", "", "| Module | Tests | Failures | Errors | Skipped |", "|---|---:|---:|---:|---:|"]
    for module, stats in sorted(modules.items()):
        summary.append(f"| `{module}` | {stats['tests']} | {stats['failures']} | {stats['errors']} | {stats['skipped']} |")
    summary.append(f"\nTotal: **{totals['tests']}**, failures: **{totals['failures']}**, errors: **{totals['errors']}**, skipped: **{totals['skipped']}**.")
    if not files:
        summary.append("**No JUnit XML: tests are not confirmed.**")
    if malformed:
        summary.append("**Some reports could not be read; the counts are incomplete.**")
    schema_files = sorted((root / "core/vault/schemas").glob("**/*.json")) if args.kind == "unit" else []
    for path in schema_files:
        try:
            schema = json.loads(path.read_text(encoding="utf-8"))
            # A generated schema is source metadata, not a device database. Retain it in the
            # annotation for inspection even when the workflow cannot upload extra artifacts.
            compact = json.dumps(schema, ensure_ascii=False, separators=(",", ":"))
            if len(compact.encode("utf-8")) <= 30_000:
                annotation("notice", "Room schema " + str(path.relative_to(root)), compact)
            summary.append(f"\nRoom schema generated: `{path.relative_to(root)}` (database version {schema.get('database', {}).get('version', '?')}).")
        except (ValueError, OSError) as error:
            annotation("warning", "Room schema unreadable", f"{path.relative_to(root)}: {error}")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as stream:
            stream.write("\n".join(summary) + "\n")

    if malformed or totals["failures"] or totals["errors"] or missing_modules or (args.fail_on_skip and totals["skipped"]):
        return 1
    if not files or totals["tests"] == 0:
        return 0 if args.allow_empty else 1
    return 0 if totals["tests"] >= args.min_tests else 1


if __name__ == "__main__":
    raise SystemExit(main())
