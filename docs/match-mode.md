# Manual match mode

Bad Watch's first match scorer is deliberately manual. The watch cannot observe the shuttle,
the opponent, or the full court from one wrist, so it never infers who won a rally. The player
taps **You** or **Them** after each point; everything after that tap is deterministic.

## What it tracks

- BWF rally scoring: first to 21, win by two, hard cap at 30, best of three.
- Games won, the side serving next, and right/even versus left/odd service court.
- The 60-second interval when the first side reaches 11.
- A 120-second between-game timer and change-of-ends reminder.
- The extra change of ends at 11 in the deciding game.
- A static, high-contrast ambient score when the watch enters always-on mode.

The standard format above was verified on 2026-07-26 against the official
[BWF Laws of Badminton](https://system.bwfbadminton.com/documents/folder_1_81/Statutes/CHAPTER-4---RULES-OF-THE-GAME/SECTION%204.1-%20Laws%20of%20Badminton.pdf).
Bad Watch does not currently implement BWF alternative competition formats; if governing rules
change, the reducer and its tests must be reviewed deliberately rather than assuming this copy is
self-updating.

For doubles, the screen says **your side** or **their side** and shows the service court. It does
not name a server or infer partner rotation; doing that correctly requires the four-player
line-up and explicit rotation state.

## Durability and undo

`core/match/BadmintonMatchEngine.kt` is a platform-free reducer. The durable source of truth is
`MatchLog`, an append-only list of explicit point and prompt-acknowledgement actions. Current
score, server, court, interval and winner are always obtained by replaying that log.

`MatchController` serializes commands through one channel and writes the new log atomically
before publishing the new score. The active document lives in app-private storage at
`files/match/active.json`. It survives Activity recreation, app process death and APK updates.
The completed score remains there until the player taps **Done**; abandoning a match requires a
confirmation.

**Undo point** removes the most recent point and all UI acknowledgements after it, then replays
the remaining actions. This means undoing a game or match point also restores the previous
server, game score, court end and interval state rather than trying to patch fields in place.

The active scoreboard is not match history and is not uploaded to the dashboard. Those are
separate product decisions; the UI does not imply that closing a scoreboard archives it.

## Verification

The rule and replay edge cases are plain JVM tests:

```bash
./gradlew :core:test --tests com.badwatch.core.match.BadmintonMatchEngineTest
```

App tests cover atomic storage, corruption handling, rapid command ordering, restart restore,
completion durability and explicit clearing:

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.badwatch.app.data.MatchStoreTest \
  --tests com.badwatch.app.domain.MatchControllerTest
```

The normal app gate remains:

```bash
./gradlew :app:lintDebug :app:assembleDebug
```

On a Pixel Watch 4, manual hardware verification covered a two-digit score, alternating service
side/court, point undo, the interval-at-11 timer, force-stop/relaunch with the same score and
continuing deadline, the tools page, abandon confirmation, and removal of the test match.
