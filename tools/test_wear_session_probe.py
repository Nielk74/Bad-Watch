import sys
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tooling"))

from wear_session_probe import (  # noqa: E402
    ACTIVITY,
    Device,
    POST_STOP_REVIEW,
    ProbeError,
    SAVED_RECAP,
    SystemWakeControls,
    classify_post_stop_screen,
    dnd_filter_for_zen_mode,
    find_stop_action,
    system_wake_suppression,
    visible_hierarchy_labels,
)


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


class WearSessionProbePostStopScreenTest(unittest.TestCase):
    def test_classifies_english_and_french_optional_review(self) -> None:
        english = ET.fromstring(
            '<hierarchy><node text="What did you play?" content-desc="" /></hierarchy>'
        )
        french = ET.fromstring(
            '<hierarchy><node text="Qu’avez-vous joué ?" content-desc="" /></hierarchy>'
        )

        self.assertEqual(classify_post_stop_screen(english), POST_STOP_REVIEW)
        self.assertEqual(classify_post_stop_screen(french), POST_STOP_REVIEW)

    def test_classifies_english_and_french_saved_recap(self) -> None:
        english = ET.fromstring(
            '<hierarchy><node text="SESSION SAVED" content-desc="" /></hierarchy>'
        )
        french = ET.fromstring(
            '<hierarchy><node text="" content-desc="SÉANCE ENREGISTRÉE" /></hierarchy>'
        )

        self.assertEqual(classify_post_stop_screen(english), SAVED_RECAP)
        self.assertEqual(classify_post_stop_screen(french), SAVED_RECAP)

    def test_saved_recap_wins_during_crossfade_and_labels_are_deduplicated(self) -> None:
        root = ET.fromstring(
            """<hierarchy>
            <node text="What did you play?" content-desc="" />
            <node text="Session saved" content-desc="Session saved" />
            </hierarchy>"""
        )

        self.assertEqual(classify_post_stop_screen(root), SAVED_RECAP)
        self.assertEqual(
            visible_hierarchy_labels(root),
            ["What did you play?", "Session saved"],
        )

    def test_unrelated_screen_is_not_mislabeled_as_evidence(self) -> None:
        root = ET.fromstring(
            '<hierarchy><node text="Bad Watch" content-desc="History" /></hierarchy>'
        )

        self.assertIsNone(classify_post_stop_screen(root))


class FakeWakeControlDevice:
    def __init__(self) -> None:
        self.controls = SystemWakeControls(
            stay_on_while_plugged_in="7",
            theater_mode_on=None,
            zen_mode=3,
        )
        self.restore_calls: list[SystemWakeControls] = []

    def system_wake_controls(self) -> SystemWakeControls:
        return self.controls

    def apply_system_wake_suppression(self) -> None:
        self.controls = SystemWakeControls(
            stay_on_while_plugged_in="0",
            theater_mode_on="1",
            zen_mode=2,
        )

    def restore_system_wake_controls(self, original: SystemWakeControls) -> None:
        self.restore_calls.append(original)
        self.controls = original


class WearSessionProbeWakeSuppressionTest(unittest.TestCase):
    def test_maps_every_android_zen_mode_to_exact_restore_filter(self) -> None:
        self.assertEqual(
            {mode: dnd_filter_for_zen_mode(mode) for mode in range(4)},
            {0: "all", 1: "priority", 2: "none", 3: "alarms"},
        )
        with self.assertRaises(ProbeError):
            dnd_filter_for_zen_mode(4)

    def test_restores_exact_controls_after_probe_error(self) -> None:
        device = FakeWakeControlDevice()
        original = device.controls
        evidence = None

        with self.assertRaisesRegex(ProbeError, "simulated monitoring failure"):
            with system_wake_suppression(device, enabled=True) as evidence:
                self.assertEqual(evidence.original, original)
                self.assertEqual(evidence.active.zen_mode, 2)
                raise ProbeError("simulated monitoring failure")

        self.assertEqual(device.restore_calls, [original])
        self.assertEqual(device.controls, original)
        self.assertIsNotNone(evidence)
        self.assertEqual(evidence.restored, original)

    def test_restores_exact_controls_after_keyboard_interrupt(self) -> None:
        device = FakeWakeControlDevice()
        original = device.controls

        with self.assertRaises(KeyboardInterrupt):
            with system_wake_suppression(device, enabled=True):
                raise KeyboardInterrupt

        self.assertEqual(device.restore_calls, [original])
        self.assertEqual(device.controls, original)

    def test_disabled_mode_does_not_touch_device(self) -> None:
        class UntouchableDevice:
            def __getattr__(self, name: str) -> object:
                raise AssertionError(f"default mode unexpectedly accessed {name}")

        with system_wake_suppression(UntouchableDevice(), enabled=False) as evidence:  # type: ignore[arg-type]
            self.assertFalse(evidence.enabled)

        self.assertEqual(
            evidence.as_report(),
            {"enabled": False, "original": None, "active": None, "restored": None},
        )


class FakeStopDevice(Device):
    def __init__(self, wakefulness: list[str]) -> None:
        super().__init__("test-serial")
        self.wakefulness_values = iter(wakefulness)
        self.commands: list[tuple[tuple[str, ...], bool]] = []

    def wakefulness(self) -> str | None:
        return next(self.wakefulness_values)

    def shell(self, *args: str, check: bool = True) -> str:
        self.commands.append((args, check))
        return ""


class WearSessionProbeVisibleStopTest(unittest.TestCase):
    def test_each_attempt_relaunches_activity_but_only_wakes_a_sleeping_display(self) -> None:
        device = FakeStopDevice(["Dozing", "Awake"])

        self.assertEqual(device.prepare_visible_stop_attempt(), "Dozing")
        self.assertEqual(device.prepare_visible_stop_attempt(), "Awake")

        wake_commands = [
            command for command, _ in device.commands if command[:3] == ("input", "keyevent", "224")
        ]
        launch_commands = [
            command for command, _ in device.commands if command[:2] == ("am", "start")
        ]
        self.assertEqual(wake_commands, [("input", "keyevent", "224")])
        self.assertEqual(
            launch_commands,
            [
                ("am", "start", "-f", "0x04000000", "-n", ACTIVITY),
                ("am", "start", "-f", "0x04000000", "-n", ACTIVITY),
            ],
        )
        self.assertTrue(all(check is False for command, check in device.commands if command[:2] == ("am", "start")))


class FakeRecapDevice(Device):
    def __init__(self, screens: list[str | None]) -> None:
        super().__init__("test-serial")
        self.screens = iter(screens)
        self.commands: list[tuple[str, ...]] = []
        self.screenshots: list[str] = []

    def post_stop_screen(self) -> tuple[str | None, list[str]]:
        screen = next(self.screens)
        labels = {
            POST_STOP_REVIEW: ["What did you play?"],
            SAVED_RECAP: ["Session saved"],
        }.get(screen, [])
        return screen, labels

    def shell(self, *args: str, check: bool = True) -> str:
        self.commands.append(args)
        return ""

    def screenshot(self, destination: Path) -> None:
        self.screenshots.append(destination.name)


class WearSessionProbeRecapCaptureTest(unittest.TestCase):
    @patch("wear_session_probe.time.sleep", return_value=None)
    def test_retains_review_then_skips_once_and_captures_settled_summary(self, _: object) -> None:
        device = FakeRecapDevice(
            [POST_STOP_REVIEW, POST_STOP_REVIEW, SAVED_RECAP, SAVED_RECAP]
        )

        review_captured = device.capture_saved_session_recap(Path("evidence"))

        self.assertTrue(review_captured)
        self.assertEqual(device.screenshots, ["post-stop-review.png", "recap.png"])
        self.assertEqual(device.commands, [("input", "keyevent", "4")])

    @patch("wear_session_probe.time.sleep", return_value=None)
    def test_captures_summary_directly_when_optional_review_is_already_resolved(self, _: object) -> None:
        device = FakeRecapDevice([SAVED_RECAP, SAVED_RECAP])

        review_captured = device.capture_saved_session_recap(Path("evidence"))

        self.assertFalse(review_captured)
        self.assertEqual(device.screenshots, ["recap.png"])
        self.assertEqual(device.commands, [])


if __name__ == "__main__":
    unittest.main()
