import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "instrumentation-to-junit.py"
spec = importlib.util.spec_from_file_location("android_protocol", SCRIPT)
protocol = importlib.util.module_from_spec(spec)
spec.loader.exec_module(protocol)


def case(name, result=0, count=1, stack=""):
    fields = f"INSTRUMENTATION_STATUS: class=Smoke\nINSTRUMENTATION_STATUS: test={name}\nINSTRUMENTATION_STATUS: numtests={count}\n"
    ending = fields + (f"INSTRUMENTATION_STATUS: stack={stack}\n" if stack else "")
    return fields + "INSTRUMENTATION_STATUS_CODE: 1\n" + ending + f"INSTRUMENTATION_STATUS_CODE: {result}\n"


class ProtocolTest(unittest.TestCase):
    def test_complete_pass_comes_from_real_start_and_finish_records(self):
        cases = protocol.parse_protocol(case("one", count=2) + case("two", count=2) + "INSTRUMENTATION_CODE: -1\n")
        root = protocol.to_xml(cases, "Smoke").getroot()
        self.assertEqual("2", root.get("tests"))
        self.assertEqual("0", root.get("failures"))
        self.assertEqual(["one", "two"], [x.get("name") for x in root.findall("testcase")])

    def test_failures_errors_and_skips_are_preserved(self):
        text = case("failed", -2, 4, "failure line\n stack line") + case("error", -1, 4, "error") + case("ignored", -3, 4) + case("assumption", -4, 4) + "INSTRUMENTATION_CODE: -1\n"
        root = protocol.to_xml(protocol.parse_protocol(text), "Smoke").getroot()
        self.assertEqual("1", root.get("failures"))
        self.assertEqual("1", root.get("errors"))
        self.assertEqual("2", root.get("skipped"))
        self.assertIn("stack line", root.find("testcase/failure").text)

    def test_ok_text_without_protocol_is_not_a_pass(self):
        with self.assertRaises(protocol.ProtocolError):
            protocol.parse_protocol("OK (8 tests)\nINSTRUMENTATION_CODE: -1\n")

    def test_crash_or_missing_final_code_is_not_complete(self):
        for text in [case("one"), case("one") + "INSTRUMENTATION_CODE: 0\n", case("one") + "INSTRUMENTATION_RESULT: shortMsg=Process crashed\nINSTRUMENTATION_CODE: -1\n"]:
            with self.assertRaises(protocol.ProtocolError):
                protocol.parse_protocol(text)

    def test_duplicate_cases_and_wrong_expected_count_are_rejected(self):
        for text in [case("one", count=2) + case("one", count=2), case("one", count=3)]:
            with self.assertRaises(protocol.ProtocolError):
                protocol.parse_protocol(text + "INSTRUMENTATION_CODE: -1\n")

    def test_unfinished_or_unmatched_tests_are_rejected(self):
        start = "INSTRUMENTATION_STATUS: class=Smoke\nINSTRUMENTATION_STATUS: test=one\nINSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS_CODE: 1\n"
        with self.assertRaises(protocol.ProtocolError):
            protocol.parse_protocol(start + "INSTRUMENTATION_CODE: -1\n")
        with self.assertRaises(protocol.ProtocolError):
            protocol.parse_protocol(start.replace("STATUS_CODE: 1", "STATUS_CODE: 0") + "INSTRUMENTATION_CODE: -1\n")


if __name__ == "__main__":
    unittest.main()
