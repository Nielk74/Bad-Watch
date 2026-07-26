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
    activate_system_wake_suppression,
    classify_post_stop_screen,
    dnd_filter_for_zen_mode,
    find_stop_action,
    stop_started_session_on_failure,
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

    @patch("wear_session_probe.time.sleep", return_value=None)
    def test_restores_exact_controls_after_probe_error(self, _: object) -> None:
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

    @patch("wear_session_probe.time.sleep", return_value=None)
    def test_restores_exact_controls_after_keyboard_interrupt(self, _: object) -> None:
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


class SequencedWakeControlDevice:
    def __init__(self, controls: list[SystemWakeControls]) -> None:
        self.controls = iter(controls)
        self.apply_calls = 0

    def apply_system_wake_suppression(self) -> None:
        self.apply_calls += 1

    def system_wake_controls(self) -> SystemWakeControls:
        return next(self.controls)


class WearSessionProbeWakeConvergenceTest(unittest.TestCase):
    @patch("wear_session_probe.time.sleep", return_value=None)
    def test_retries_when_theater_does_not_latch_then_requires_stable_reads(
        self,
        _: object,
    ) -> None:
        expected = SystemWakeControls("0", "1", 2)
        theater_fell_back = SystemWakeControls("0", "0", 2)
        device = SequencedWakeControlDevice(
            [
                theater_fell_back,
                expected,
                theater_fell_back,
                expected,
                expected,
            ]
        )

        active = activate_system_wake_suppression(  # type: ignore[arg-type]
            device,
            max_attempts=3,
            settle_seconds=0,
        )

        self.assertEqual(active, expected)
        self.assertEqual(device.apply_calls, 3)

    @patch("wear_session_probe.time.sleep", return_value=None)
    def test_fails_after_bounded_attempts_with_last_observed_controls(self, _: object) -> None:
        theater_fell_back = SystemWakeControls("0", "0", 2)
        device = SequencedWakeControlDevice([theater_fell_back, theater_fell_back])

        with self.assertRaisesRegex(
            ProbeError,
            "did not stabilize after 2 attempts.*theaterModeOn.*'0'",
        ):
            activate_system_wake_suppression(  # type: ignore[arg-type]
                device,
                max_attempts=2,
                settle_seconds=0,
            )

        self.assertEqual(device.apply_calls, 2)


class FakeFailureCleanupDevice:
    def __init__(
        self,
        *,
        running: bool = True,
        journal: dict[str, object] | None = None,
        stop_error: BaseException | None = None,
    ) -> None:
        self.running = running
        self.journal = journal
        self.stop_error = stop_error
        self.start_calls = 0
        self.stop_calls = 0

    def is_session_service_running(self) -> bool:
        return self.running

    def read_active_journal(self) -> dict[str, object] | None:
        return self.journal

    def start_session(self) -> None:
        self.start_calls += 1
        self.running = True

    def stop_session_through_ui(self) -> None:
        self.stop_calls += 1
        if self.stop_error is not None:
            raise self.stop_error
        self.running = False
        self.journal = None


class OrderedFailureDevice(FakeWakeControlDevice):
    def __init__(self) -> None:
        super().__init__()
        self.running = True
        self.events: list[str] = []

    def apply_system_wake_suppression(self) -> None:
        self.events.append("apply")
        super().apply_system_wake_suppression()

    def restore_system_wake_controls(self, original: SystemWakeControls) -> None:
        self.events.append("restore")
        super().restore_system_wake_controls(original)

    def is_session_service_running(self) -> bool:
        return self.running

    def read_active_journal(self) -> dict[str, object] | None:
        return None

    def stop_session_through_ui(self) -> None:
        self.events.append("visible-stop")
        self.running = False


class Python310StyleProbeError(ProbeError):
    # Simulate the absence of BaseException.add_note while this suite runs on Python 3.11+.
    add_note = None


class WearSessionProbeFailureCleanupTest(unittest.TestCase):
    def test_probe_error_is_rethrown_after_visible_stop_save(self) -> None:
        device = FakeFailureCleanupDevice()
        original = ProbeError("original monitoring failure")

        with self.assertRaises(ProbeError) as raised:
            with stop_started_session_on_failure(device):  # type: ignore[arg-type]
                raise original

        self.assertIs(raised.exception, original)
        self.assertEqual(device.stop_calls, 1)
        self.assertFalse(device.running)

    def test_journal_only_failure_recovers_exact_session_before_visible_stop_save(self) -> None:
        device = FakeFailureCleanupDevice(
            running=False,
            journal={"checkpoint": {"sessionId": "recover-me"}},
        )
        original = ProbeError("service disappeared")

        with self.assertRaises(ProbeError) as raised:
            with stop_started_session_on_failure(device):  # type: ignore[arg-type]
                raise original

        self.assertIs(raised.exception, original)
        self.assertEqual(device.start_calls, 1)
        self.assertEqual(device.stop_calls, 1)
        self.assertFalse(device.running)
        self.assertIsNone(device.journal)

    @patch("wear_session_probe.time.sleep", return_value=None)
    def test_wake_controls_restore_before_failure_cleanup_wakes_and_stops(self, _: object) -> None:
        device = OrderedFailureDevice()

        with self.assertRaisesRegex(ProbeError, "monitoring failed"):
            with stop_started_session_on_failure(device):  # type: ignore[arg-type]
                with system_wake_suppression(device, enabled=True):
                    raise ProbeError("monitoring failed")

        self.assertEqual(device.events, ["apply", "restore", "visible-stop"])
        self.assertFalse(device.running)

    def test_keyboard_interrupt_is_rethrown_after_visible_stop_save(self) -> None:
        device = FakeFailureCleanupDevice()
        original = KeyboardInterrupt()

        with self.assertRaises(KeyboardInterrupt) as raised:
            with stop_started_session_on_failure(device):  # type: ignore[arg-type]
                raise original

        self.assertIs(raised.exception, original)
        self.assertEqual(device.stop_calls, 1)
        self.assertFalse(device.running)

    @patch("wear_session_probe.print")
    def test_cleanup_failure_is_not_allowed_to_replace_original_error(self, _: object) -> None:
        device = FakeFailureCleanupDevice(stop_error=ProbeError("stop UI unavailable"))
        original = ProbeError("original suppression failure")

        with self.assertRaises(ProbeError) as raised:
            with stop_started_session_on_failure(device):  # type: ignore[arg-type]
                raise original

        self.assertIs(raised.exception, original)
        self.assertEqual(device.stop_calls, 1)
        self.assertIn("stop UI unavailable", "\n".join(original.__notes__))

    @patch("wear_session_probe.print")
    def test_python_310_without_add_note_still_preserves_and_reports_primary_error(
        self,
        print_mock: object,
    ) -> None:
        device = FakeFailureCleanupDevice(stop_error=ProbeError("stop UI unavailable"))
        original = Python310StyleProbeError("original suppression failure")

        with self.assertRaises(Python310StyleProbeError) as raised:
            with stop_started_session_on_failure(device):  # type: ignore[arg-type]
                raise original

        self.assertIs(raised.exception, original)
        self.assertEqual(device.stop_calls, 1)
        self.assertTrue(getattr(print_mock, "called"))


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
