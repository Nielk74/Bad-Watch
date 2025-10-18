# Bad Watch Usage Guide

## Before You Start
- Grant the app body sensor access when prompted during the first launch.
- Wear the Pixel Watch 3 snugly on your wrist to ensure accurate heart-rate readings.
- Warm up with a few light swings so the classifier captures a baseline.

## Training Session Workflow
1. Launch **Bad Watch** on the watch and tap **Start Session**.
2. The live HUD shows:
   - **Heart/Avg/Max** heart-rate values.
   - **Fatigue/Effort/Recovery** scores (0–100%).
   - Latest detected shot type and total shot count.
   - Dominant heart-rate zone indicator.
3. Continue playing; the app automatically recognises smashes, clears, drops, drives, and backhand drives using gyroscope vectors plus heart-rate deltas.
4. Tap **Stop & Save** to end the session and store the summary, or **Stop & Discard** to finish without saving. Use **Abort** to cancel a session mid-way without generating a summary.

## Coach Insights
- **Fatigue** rises as your average and peak heart-rate climb relative to baseline.
- **Effort** blends fatigue with peak intensity to show how hard you pushed the rally.
- **Recovery** analyses recent heart-rate slope; higher is better, signalling you’re ready for the next rally.
- A sudden jump in fatigue with falling recovery hints at overreaching—consider a rest interval.

## History & Data Export
- The latest sessions appear on the idle screen. Tap **Clear history** to wipe the stored log (up to 40 entries are kept automatically).
- Session data is stored locally via Jetpack DataStore (`training_sessions.json`). You can export it by pulling the file with `adb` for external analysis (Future versions will add companion sync).

## Tips for Accurate Detection
- Maintain consistent wrist orientation; extreme pronation/supination outside badminton swings may misclassify.
- Keep the watch firmware updated so heart-rate sampling stays reliable.
- If swings aren’t detected, check the permissions, ensure the watch is tight, and give the classifier 2–3 swings to recalibrate.

## Troubleshooting
- **No heart-rate data**: Re-grant BODY_SENSORS permission in settings or clean the watch back.
- **Classifier lag**: reduce other intensive apps running on the watch; Bad Watch uses coroutine pipelines and should stay responsive.
- **Unexpected crashes**: run `adb logcat | grep BadWatch` and file an issue with the captured stack trace.
