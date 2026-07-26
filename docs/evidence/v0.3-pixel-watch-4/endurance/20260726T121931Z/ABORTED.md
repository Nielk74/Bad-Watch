# Disqualified endurance attempt

This run began at `2026-07-26T12:19:31Z` and was intentionally stopped after
approximately 28.5 minutes. It is **not** a release-gate result and produced no
`report.json`.

The app remained healthy and continued recording, but the next probe sample saw
Android `Awake`. The strict endurance gate requires every timed sample to be
`Dozing`, so the run was immediately disqualified rather than weakening the
criterion or presenting a partial result.

Android system logs attribute the wake to a physical USB/dock reconnect around
`2026-07-26T14:47:46+02:00`: the USB port disconnected, Android recorded
`WAKE_REASON_DOCK`, the charging UI launched, and the port reconnected several
times. The following sample had returned to `Dozing`. This was an external test
harness interruption, not an app-service failure.

The probe's tested cleanup path stopped and saved the partial session, removed
the active-session journal, stopped the foreground service, and restored the
original watch controls (`stay_on_while_plugged_in=7`, `theater_mode_on=0`,
`zen_mode=0`). Only the start screenshot is retained so this failed attempt is
auditable without being confused with passing evidence.
