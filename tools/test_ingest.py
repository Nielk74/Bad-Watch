import unittest

from ingest import swing_to_row


def sample(timestamp: int) -> dict:
    return {
        "timestampMillis": timestamp,
        "gyro": {"x": 0.2, "y": 0.4, "z": 4.0},
        "accel": {"x": 0.0, "y": 0.0, "z": 9.8},
        "heartRateBpm": None,
    }


class IngestMetadataTest(unittest.TestCase):
    def test_versioned_capture_keeps_participant_protocol_and_watch_separate(self) -> None:
        export = {
            "deviceId": "install-7",
            "participantId": "person-3",
            "dataUse": "SelfHostedModelTraining",
            "profile": {"handedness": "Right"},
            "protocol": {
                "name": "single-stroke-repetitions",
                "version": 1,
                "context": "SingleStrokeDrill",
            },
            "watch": {"manufacturer": "Google", "model": "Pixel Watch", "sdkInt": 36},
        }
        swing = {
            "label": "Smash",
            "samples": [sample(timestamp) for timestamp in (0, 10, 20, 30, 40)],
        }

        row = swing_to_row(swing, export)

        self.assertIsNotNone(row)
        assert row is not None
        self.assertEqual(row.participant_id, "person-3")
        self.assertEqual(row.device_id, "install-7")
        self.assertEqual(row.protocol_version, 1)
        self.assertEqual(row.capture_context, "SingleStrokeDrill")
        self.assertEqual(row.watch_model, "Pixel Watch")

    def test_legacy_capture_does_not_invent_a_participant(self) -> None:
        export = {
            "deviceId": "old-install",
            "profile": {"handedness": "Left"},
        }
        swing = {
            "label": "Clear",
            "samples": [sample(timestamp) for timestamp in (0, 10, 20, 30, 40)],
        }

        row = swing_to_row(swing, export)

        self.assertIsNotNone(row)
        assert row is not None
        self.assertEqual(row.participant_id, "")
        self.assertEqual(row.device_id, "old-install")
        self.assertEqual(row.protocol_name, "legacy-unversioned")
        self.assertEqual(row.protocol_version, 0)
        self.assertEqual(row.data_use, "LocalOnly")


if __name__ == "__main__":
    unittest.main()
