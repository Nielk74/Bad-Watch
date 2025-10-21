# Bad Watch UI & Insights Overhaul Plan

## Objectives
- Elevate visual identity with a modern Wear OS aesthetic that remains legible during play.
- Streamline in-session interactions for faster decision making and reduced clutter.
- Deepen post-session insights and trend analysis to support sustained training improvements.
- Lay the groundwork for ML-driven shot recognition that can eventually supersede rule-based logic.

## Phase 1 – Brand & Core UI Refresh
- Define a cohesive visual language (color palette, typography, iconography) that respects circular displays and accessibility contrast targets.
- Redesign the adaptive app icon (shuttlecock + heartbeat motif), validate on-device previews, and update launcher assets.
- Update Compose theme primitives: migrate to modern button/chip styles with `ButtonDefaults.filledTonalButtonColors`, pill-shaped actions, and haptic-confirmed toggles.
- Introduce micro-animations (shot detection pulses, state transitions) via `AnimatedContent` and `rememberInfiniteTransition`, balancing fluidity with battery constraints.
- Add Compose preview snapshots and Wear OS screenshot tests to lock in the new visual baseline.

## Phase 2 – Session Flow & HUD Experience
- Simplify the training flow: consolidate Start/Stop/Abort into a single contextual primary action and display warm-up progress inline.
- Recompose the live HUD into vertically stacked cards (heart-rate, fatigue, shot streak) with swipeable detail panes for secondary metrics.
- Ship contextual help overlays (long-press or info chip) that clarify metric definitions and optimal sensor placement.
- Ensure key actions remain one tap away, with vibration feedback confirming state changes during play.

## Phase 3 – Insights Expansion
- Create a post-session recap carousel highlighting top shots, rally consistency, fatigue trends, and quick export/share options.
- Add rolling five-session trend lines for fatigue vs. recovery, shot distribution heatmaps, and effort tier summaries.
- Implement “Focus Areas” logic that detects underused shot types, inconsistent rally durations, or early fatigue onset, and suggests drills.
- Introduce comparative benchmarks against personal bests and user-defined goals stored in DataStore; surface progress deltas in history.
- Provide export presets (JSON and CSV) plus companion summaries for downstream analysis on phone or coaching platforms.

## Phase 4 – ML Classifier Roadmap
- Collect opt-in labeled swing datasets by augmenting current gyroscope/heart-rate traces with lightweight tagging UX.
- Prototype classical ML baselines (Random Forest, SVM) prior to deploying compact deep models (TFLite Micro CNN/LSTM) for on-device inference.
- Investigate transfer learning from tennis swing datasets to bootstrap badminton recognition; fine-tune using collected samples.
- Integrate TensorFlow Lite Model Binding in the core module with a fallback to the existing rule-based classifier and telemetry to monitor accuracy.
- Establish an evaluation loop: cross-validate with recorded sessions, track precision/recall per shot type, and prompt users for feedback on dubious predictions.

## Immediate Next Steps
1. Align on visual style guide deliverables (palette, typography, icon concepts, motion principles).
2. Prioritize the insights/features to ship in Phase 2 and map supporting data requirements.
3. Kick off the data collection framework to capture labeled swings for upcoming ML experiments.
