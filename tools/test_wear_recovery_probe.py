import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tooling"))

from wear_recovery_probe import evaluate_process_absence_evidence  # noqa: E402


class WearRecoveryProbeProcessAbsenceTest(unittest.TestCase):
    def test_accepts_matching_gap_that_covers_the_conservative_recovery_window(self) -> None:
        gaps = [
            {
                "startedAtMillis": 1_000,
                "endedAtMillis": 5_000,
            }
        ]

        evidence = evaluate_process_absence_evidence(
            session_gaps=gaps,
            checkpoint_gaps=gaps,
            journal_updated_at_millis=1_200,
            forced_stop_at_millis=2_000,
            restart_requested_at_millis=4_000,
        )

        self.assertTrue(evidence.passed)
        self.assertTrue(evidence.checkpoint_exact_match)
        self.assertTrue(evidence.covers_journal_to_restart)
        self.assertTrue(evidence.overlaps_forced_stop)
        self.assertEqual(evidence.as_report()["processAbsenceGapTotalMillis"], 4_000)
        self.assertEqual(
            evidence.as_report()["matchedProcessAbsenceGap"],
            gaps[0],
        )

    def test_missing_gap_provenance_cannot_pass(self) -> None:
        evidence = evaluate_process_absence_evidence(
            session_gaps=None,
            checkpoint_gaps=None,
            journal_updated_at_millis=1_200,
            forced_stop_at_millis=2_000,
            restart_requested_at_millis=4_000,
        )

        self.assertFalse(evidence.passed)
        self.assertFalse(evidence.checkpoint_exact_match)
        self.assertIn(
            "session.processAbsenceGaps is missing",
            evidence.validation_errors,
        )
        self.assertIn(
            "after checkpoint aggregator.processAbsenceGaps is missing",
            evidence.validation_errors,
        )

    def test_malformed_intervals_cannot_pass(self) -> None:
        malformed = [
            {
                "startedAtMillis": 5_000,
                "endedAtMillis": 5_000,
            }
        ]

        evidence = evaluate_process_absence_evidence(
            session_gaps=malformed,
            checkpoint_gaps=malformed,
            journal_updated_at_millis=1_200,
            forced_stop_at_millis=2_000,
            restart_requested_at_millis=4_000,
        )

        self.assertFalse(evidence.passed)
        self.assertFalse(evidence.as_report()["processAbsenceIntervalsValid"])
        self.assertTrue(
            any("must be after its start" in error for error in evidence.validation_errors)
        )

    def test_checkpoint_and_saved_session_interval_mismatch_cannot_pass(self) -> None:
        session_gaps = [{"startedAtMillis": 1_000, "endedAtMillis": 5_000}]
        checkpoint_gaps = [{"startedAtMillis": 1_000, "endedAtMillis": 4_500}]

        evidence = evaluate_process_absence_evidence(
            session_gaps=session_gaps,
            checkpoint_gaps=checkpoint_gaps,
            journal_updated_at_millis=1_200,
            forced_stop_at_millis=2_000,
            restart_requested_at_millis=4_000,
        )

        self.assertFalse(evidence.passed)
        self.assertFalse(evidence.checkpoint_exact_match)
        self.assertTrue(evidence.covers_journal_to_restart)
        self.assertTrue(evidence.overlaps_forced_stop)


if __name__ == "__main__":
    unittest.main()
