import json
import unittest
from pathlib import Path

from train import evaluate_candidate_gate


class CandidateGateTest(unittest.TestCase):
    def setUp(self) -> None:
        path = Path(__file__).with_name("model_acceptance.json")
        self.criteria = json.loads(path.read_text())

    def test_offline_gate_can_pass_but_never_claims_deployment_ready(self) -> None:
        report = {
            "Smash": {"recall": 0.82},
            "Clear": {"recall": 0.79},
            "macro avg": {"f1-score": 0.84},
        }

        gate = evaluate_candidate_gate(
            class_counts={"Smash": 350, "Clear": 340},
            participant_count=6,
            report=report,
            grouping_name="participant",
            criteria=self.criteria,
        )

        self.assertTrue(gate["offlineGatePassed"])
        self.assertFalse(gate["fieldEventValidationPassed"])
        self.assertFalse(gate["deploymentReady"])

    def test_device_grouped_smoke_cannot_pass_offline_gate(self) -> None:
        report = {
            "Smash": {"recall": 1.0},
            "Clear": {"recall": 1.0},
            "macro avg": {"f1-score": 1.0},
        }

        gate = evaluate_candidate_gate(
            class_counts={"Smash": 1_000, "Clear": 1_000},
            participant_count=20,
            report=report,
            grouping_name="device smoke-test",
            criteria=self.criteria,
        )

        self.assertFalse(gate["checks"]["participant_grouping"])
        self.assertFalse(gate["offlineGatePassed"])

    def test_thin_class_or_weak_recall_blocks_candidate(self) -> None:
        report = {
            "Smash": {"recall": 0.95},
            "Clear": {"recall": 0.60},
            "macro avg": {"f1-score": 0.81},
        }

        gate = evaluate_candidate_gate(
            class_counts={"Smash": 500, "Clear": 299},
            participant_count=5,
            report=report,
            grouping_name="participant",
            criteria=self.criteria,
        )

        self.assertFalse(gate["checks"]["minimum_examples_per_class"])
        self.assertFalse(gate["checks"]["minimum_per_class_recall"])
        self.assertFalse(gate["offlineGatePassed"])


if __name__ == "__main__":
    unittest.main()
