import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("apk_signatures", Path(__file__).resolve().parents[1] / "verify-apk-signatures.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

A = "a" * 64
B = "b" * 64


class SignatureTest(unittest.TestCase):
    def test_old_and_new_apksigner_formats_match(self):
        self.assertEqual([A], module.compare(f"Signer #1 certificate SHA-256 digest: {A}\n", f"V2 Signer: certificate SHA-256 digest: {A}\n"))

    def test_multiple_schemes_do_not_count_as_different_certificates(self):
        self.assertEqual([A], module.compare(f"V2 Signer: certificate SHA-256 digest: {A}\nV3 Signer: certificate SHA-256 digest: {A}\n", f"V2 Signer: certificate SHA-256 digest: {A}\n"))

    def test_mismatch_and_unrecognized_output_fail_closed(self):
        with self.assertRaises(ValueError):
            module.compare(f"V2 Signer: certificate SHA-256 digest: {A}\n", f"V2 Signer: certificate SHA-256 digest: {B}\n")
        with self.assertRaises(ValueError):
            module.compare("Signer #1 certificate SHA-1 digest: short\n", "no signer")

    def test_source_stamp_is_not_confused_with_application_signer(self):
        self.assertEqual([A], module.certificates(f"Source Stamp Signer: certificate SHA-256 digest: {B}\nV2 Signer: certificate SHA-256 digest: {A}\n"))


if __name__ == "__main__":
    unittest.main()
