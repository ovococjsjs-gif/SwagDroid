#!/usr/bin/env python3
"""Convert the real AndroidJUnitRunner ADB protocol to JUnit XML.

No synthetic passing tests: a complete run, matching start/finish records and expected
case count are mandatory. This avoids AGP's separately downloaded host UTP runtime,
not the Android test runner or any test assertion.
"""
import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


class ProtocolError(ValueError):
    pass


def parse_protocol(text):
    bundle = {}
    current_field = None
    started = set()
    finished = {}
    expected = None
    terminal = None
    fatal = []
    for raw in text.splitlines():
        line = raw.rstrip("\r")
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, sep, value = line.removeprefix("INSTRUMENTATION_STATUS: ").partition("=")
            if not sep:
                raise ProtocolError("Malformed instrumentation status field")
            bundle[key] = value
            current_field = key
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = int(line.removeprefix("INSTRUMENTATION_STATUS_CODE: "))
            if "numtests" in bundle:
                count = int(bundle["numtests"])
                if expected is not None and expected != count:
                    raise ProtocolError("Runner changed its declared test count")
                expected = count
            key = (bundle.get("class"), bundle.get("test"))
            if not all(key):
                raise ProtocolError(f"Status {code} lacks a test identity: {bundle}")
            if code == 1:
                if key in started or key in finished:
                    raise ProtocolError(f"Duplicate test start: {key}")
                started.add(key)
            elif code in (0, -1, -2, -3, -4):
                if key not in started or key in finished:
                    raise ProtocolError(f"Unmatched/duplicate test finish: {key}")
                finished[key] = {"class": key[0], "name": key[1], "code": code, "stack": bundle.get("stack", "")}
            else:
                raise ProtocolError(f"Unsupported runner status code {code}")
            bundle = {}
            current_field = None
        elif line.startswith("INSTRUMENTATION_CODE: "):
            if terminal is not None:
                raise ProtocolError("Multiple final instrumentation codes")
            terminal = int(line.removeprefix("INSTRUMENTATION_CODE: "))
            current_field = None
        elif line.startswith("INSTRUMENTATION_FAILED:") or line.startswith("INSTRUMENTATION_ABORTED:"):
            fatal.append(line)
            current_field = None
        elif line.startswith("INSTRUMENTATION_RESULT:"):
            if "shortMsg=" in line or "longMsg=" in line:
                fatal.append(line)
            current_field = None
        elif current_field == "stack":
            bundle["stack"] += "\n" + line
    if fatal:
        raise ProtocolError("\n".join(fatal))
    if terminal != -1:
        raise ProtocolError(f"Runner did not finish normally (instrumentation code {terminal})")
    if not finished or started != set(finished):
        raise ProtocolError("No complete run: some tests never started or never finished")
    if expected is None or expected != len(finished):
        raise ProtocolError(f"Declared {expected} tests, completed {len(finished)}")
    return list(finished.values())


def to_xml(cases, suite_name):
    failures = sum(c["code"] == -2 for c in cases)
    errors = sum(c["code"] == -1 for c in cases)
    skipped = sum(c["code"] in (-3, -4) for c in cases)
    suite = ET.Element("testsuite", name=suite_name, tests=str(len(cases)), failures=str(failures), errors=str(errors), skipped=str(skipped))
    properties = ET.SubElement(suite, "properties")
    ET.SubElement(properties, "property", name="reportSource", value="AndroidJUnitRunner ADB instrumentation protocol")
    for case in cases:
        element = ET.SubElement(suite, "testcase", classname=case["class"], name=case["name"])
        tag = {-1: "error", -2: "failure", -3: "skipped", -4: "skipped"}.get(case["code"])
        if tag:
            message = case["stack"] or f"Android runner status {case['code']}"
            ET.SubElement(element, tag, message=message.splitlines()[0]).text = message
    return ET.ElementTree(suite)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--class-name", required=True)
    parser.add_argument("--min-tests", type=int, default=1)
    args = parser.parse_args()
    try:
        cases = parse_protocol(args.input.read_text(errors="replace"))
        if len(cases) < args.min_tests or any(c["class"] != args.class_name for c in cases):
            raise ProtocolError("The expected smoke class/case count was not executed")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        to_xml(cases, args.class_name).write(args.output, encoding="utf-8", xml_declaration=True)
        print(f"AndroidJUnitRunner completed {len(cases)} real test cases: {args.class_name}")
        return 0 if all(c["code"] == 0 for c in cases) else 1
    except (ProtocolError, OSError, ValueError) as error:
        message = str(error)[:3000].replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print("::error title=Android instrumentation protocol::" + message)
        return 1


if __name__ == "__main__":
    sys.exit(main())
