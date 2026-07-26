# Superseded endurance attempt

This run began at `2026-07-26T13:00:35Z` and was intentionally stopped after
21 passing timed intervals. It is **not** a release-gate result and produced no
`report.json`.

Every observed interval remained `Dozing`, kept the foreground health service,
and advanced the durable sensor checkpoint from 0 to 127,177 samples. The run
was nevertheless stopped because the concurrent final source audit found that
the visible activity-composition legend said **No play** for a residual that
actually means **No detected play**. Continuing for three hours against an APK
that was about to be corrected would not prove the final source.

The probe's tested interrupt cleanup stopped and saved the partial session,
removed the active-session journal, stopped the foreground service, and restored
the exact original watch controls (`stay_on_while_plugged_in=7`,
`theater_mode_on=0`, `zen_mode=0`). Only the start screenshot is retained so the
attempt remains auditable without being presented as passing evidence.
