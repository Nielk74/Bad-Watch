import sys
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tooling"))

from wear_session_probe import (  # noqa: E402
    ACTIVITY,
    Device,
    ProbeError,
    SystemWakeControls,
    dnd_filter_for_zen_mode,
    find_stop_action,
    system_wake_suppression,
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


if __name__ == "__main__":
    unittest.main()
