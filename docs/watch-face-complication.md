# Weekly hits watch-face complication

Bad Watch exposes one config-free Wear OS complication named **7-day detected hits**. Add it
from the complication picker for any watch-face slot that accepts short or long text. Tapping
the complication opens Bad Watch.

## What the number means

The complication shows detector events from sessions that started within the rolling seven
days ending now. It uses the same files as the in-app session history and applies the player's
saved review corrections:

- false-hit corrections and edge trims remove detector events from the total;
- reported missed hits are not added, because the watch did not detect them;
- sessions marked unusable are excluded;
- an empty window says `NO PLAY` rather than implying a measured zero.

The compact slot shows a readable count such as `184` or `1.2k` under `7D HITS`. A long-text
slot also shows the number of included sessions. This is intentionally a literal activity
counter, not an inferred training load, skill score, calorie estimate, or readiness claim.

## Refresh behavior

Wear OS may refresh the provider every 30 minutes. Bad Watch also requests an immediate
refresh after a durable session save, edit, or deletion when the application process is
running. Watch faces still control when the updated data is rendered, so a short delay is
normal.

If session files cannot be read, the provider returns Wear OS `NoData` with preview content
instead of presenting a misleading zero. The preview (`184`, three sessions) is illustrative
picker content only and is never used as live data.

## Implementation and verification

The Android service is
`app/src/main/kotlin/com/badwatch/app/complication/WeeklyHitsComplicationDataSourceService.kt`.
Rolling-window aggregation and all display copy are Android-free in
`WeeklyHitsComplicationModel.kt`, with JVM coverage in
`WeeklyHitsComplicationModelTest.kt` for time boundaries, correction semantics, unusable
sessions, empty versus measured-zero state, and compact number formatting.

The provider is declared in `app/src/main/AndroidManifest.xml` with the Wear OS bind
permission, short- and long-text metadata, and no configuration activity.

The service declaration, five-minute minimum for scheduled refreshes, and push-update API
follow Android's [Wear OS complication data-source guidance](https://developer.android.com/training/wearables/complications/exposing-data).
