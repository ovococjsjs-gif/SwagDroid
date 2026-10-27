#!/usr/bin/env python3
"""Compare public signer fingerprints emitted by successful apksigner verify calls."""
import argparse
from pathlib import Path
import re

# Android Build Tools have used both 'Signer #1' and 'V2 Signer' headings.
SIGNER = re.compile(r"^(?:Signer #\d+|V[1-4] Signer(?: #\d+)?):? certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$", re.MULTILINE)


def certificates(text):
    return sorted({digest.lower() for digest in SIGNER.findall(text)})


def compare(app_text, test_text):
    app = certificates(app_text)
    test = certificates(test_text)
    if not app or not test:
        raise ValueError("apksigner output did not contain recognized SHA-256 signing certificates")
    if app != test:
        raise ValueError("Debug target and instrumentation APK signing certificates differ")
    return app


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--app", type=Path, required=True)
    parser.add_argument("--test", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        digests = compare(args.app.read_text(), args.test.read_text())
        args.output.write_text(",".join(digests))
        print("Verified matching Debug/instrumentation signing certificates")
        return 0
    except (OSError, ValueError) as error:
        message = str(error).replace("%", "%25").replace("\n", "%0A")
        print("::error title=Android APK signing::" + message)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
