# Final-source powered lifecycle observation

This run used the byte-matched final Wear APK from source checkpoint `48d101f` on the Pixel
Watch 4 described by the parent evidence ledger. It was intentionally stopped and saved before
the originally requested 180 minutes because that duration was dropped as a v0.3 release
requirement. It is accepted as a final-source lifecycle smoke, not represented as a completed
three-hour probe and not used to claim battery endurance.

The ordinary probe process did not reach its report-writing phase, so this directory deliberately
contains no `report.json`. The retained console observations sampled the same journal at one-minute
intervals. The last ordinary interval at about 51.8 minutes reported 309,595 processed samples;
the explicit pre-stop device snapshot below was taken later.

## Retained facts

| Field | Observation |
| --- | --- |
| Session ID | `98904836-6977-4d60-8e73-315e3274895d` |
| Export | `1785073031160-98904836-6977-4d60-8e73-315e3274895d.json` |
| Persisted start/end | `1785073031160..1785076441445` |
| Persisted duration | 3,410,285 ms (`56:50.285`) |
| Final checkpoint before stop | 339,595 processed samples |
| Final wakefulness | `Dozing` |
| Final service state | running, foreground, health type `0x100` |
| Charger state | AC powered, 100%; no battery claim |
| Process-absence gaps | none |
| Save result | exactly one new session export; service and active journal absent afterward |
| Post-stop surfaces | optional review and settled saved Summary both captured |
| Wake controls | observed active `0/1/2`; restored to original `7/0/0` |

`start.png`, `post-stop-review.png`, and `recap.png` are native 480 x 480 device captures from
this session. The original `recap.png` exposed that the three-column metric row clipped `56:50`.
Release source `19ec9d4` reflows durations of ten minutes or more into a full-width row; its
byte-matched installed debug APK SHA-256 is
`bd3b5aa0d3b5815325ed789016e573453534efe4de8617198e475836bcb10b8b`, and `recap-fixed.png`
physically verifies the complete `56:50` value against the same saved session. The full three-hour
run retained at `../20260726T071128Z/report.json` remains the machine-readable long-duration
corroboration, but it ran on pre-final checkpoint `6f6f6cd`.

The v0.3 release decision therefore rests on the combination of this final-source 56-minute smoke,
the earlier passing 180-minute lifecycle report, the final-source process-recovery report, and the
complete software gate. A 180-minute final-source report and any unpowered battery measurement
remain unclaimed follow-up evidence rather than release blockers.
