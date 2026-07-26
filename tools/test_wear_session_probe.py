import sys
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tooling"))

from wear_session_probe import find_stop_action  # noqa: E402


class WearSessionProbeStopActionTest(unittest.TestCase):
    def test_finds_compact_english_button_by_full_semantic_name(self) -> None:
        root = ET.fromstring(
            """<hierarchy><node text="" content-desc="Stop &amp; save" bounds="[0,362][480,474]">
            <node text="Finish" content-desc="" bounds="[189,395][291,438]" />
            </node></hierarchy>"""
        )

        target = find_stop_action(root)

        self.assertIsNotNone(target)
        self.assertEqual(target.attrib["bounds"], "[0,362][480,474]")

    def test_finds_compact_french_button_by_full_semantic_name(self) -> None:
        root = ET.fromstring(
            """<hierarchy><node text="" content-desc="Arrêter et enregistrer"
            bounds="[0,362][480,474]"><node text="Finir" /></node></hierarchy>"""
        )

        target = find_stop_action(root)

        self.assertIsNotNone(target)
        self.assertEqual(target.attrib["content-desc"], "Arrêter et enregistrer")

    def test_keeps_legacy_visible_label_compatibility(self) -> None:
        root = ET.fromstring(
            """<hierarchy><node text="Stop &amp; save" content-desc=""
            bounds="[0,362][480,474]" /></hierarchy>"""
        )

        self.assertIsNotNone(find_stop_action(root))


if __name__ == "__main__":
    unittest.main()
