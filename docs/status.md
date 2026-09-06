# Status, and what is not verified

Ply is 0.1.0. It builds, its tests pass, and the parts below are honest about which of it
has met a real phone.

## Verified

- Release build produces a signed APK, 3.6 MB, with the exercise library inside it.
- The merged manifest contains **no** `INTERNET` and no `ACCESS_NETWORK_STATE`, confirmed by
  dumping permissions out of the built APK rather than by reading the source manifest.
- The no-network Gradle guard runs as part of `assemble` and reports zero findings.
- 42 unit tests pass over units, 1RM estimation, record detection, volume aggregation, plate
  loading and step reconciliation.
- The device has the hardware sensors this depends on: `android.sensor.step_counter` and
  `step_detector`, both `ACTIVITY_RECOGNITION`-gated, on a Pixel 10 running GrapheneOS
  (Android 17, SDK 37). A pre-existing pedometer was observed holding the counter with a
  one-hour batching period, so batched reads work on this hardware.

## Not verified — needs the phone in hand

Everything in this section is written but has not been run on a device. The phone
disconnected from adb before the first install completed.

1. **First launch and the exercise seed.** 876 rows inserted from the asset on first run.
   Nothing has confirmed the parse succeeds against the real file, how long the insert
   takes, or that the picker is populated afterwards.
2. **The logging screen end to end.** The tap count in [logging.md](logging.md) is a claim
   about a design, not a measurement of a build.
3. **The rest timer.** Whether the chronometer counts down correctly in the shade, whether
   the exact alarm fires on time through a doze, whether `+30s` and `Skip` behave, and
   whether it survives the screen going off — which is its entire reason for existing.
4. **Step counting.** No reading has been taken. The reconciler's logic is tested; the
   sensor plumbing around it is not. Specifically unverified: whether a reading arrives
   within the four-second timeout on a counter that has not moved recently, and whether the
   periodic worker survives GrapheneOS's battery handling.
5. **A real reboot.** The reboot path is unit-tested against synthetic readings. It has
   never been exercised by an actual restart.
6. **The widget.** Never placed on a home screen.
7. **Material 3 Expressive on a real wallpaper.** The theme compiles; nothing has looked at
   it.

## Known gaps, in rough priority order

1. **No routine editing UI.** The `routines` and `routine_items` tables exist, the repository
   can read them, and there is no screen to create one. Sessions are freeform until there is.
2. **No exercise detail screen.** The instructions are in the database — the entire reason
   the dataset is worth 800 KB — and nothing displays them yet.
3. **No estimate trend and no volume screen.** `estimateTrend` and `volumeForWeek` are
   written and queried by nothing. The arithmetic and the chart component both exist.
4. **No plate calculator UI.** `Plates` is written and tested; nothing calls it.
5. **No bodyweight or measurement entry.** The tables and DAO methods exist; no screen does.
   This also means `bodyweightLoaded` exercises currently record a null bodyweight, so a
   pull-up logs as its added weight only.
6. **No export.** With backup deliberately off, export is the *only* way data leaves a phone,
   which makes its absence the most serious gap here. Until it exists, a factory reset loses
   everything.
7. **No undo on deleting a set.** One tap on the row's cross and it is gone.
8. **The hourly step shape is approximate.** Steps land in the hour they were read in, not
   walked in. See [movement.md](movement.md); the fix is a batched listener and the
   reconciler already accepts the shape it would produce.
9. **`targetSdk` is 36 while the device runs 37**, so Android 17 compatibility behaviours
   apply. Moving to 37 should be a deliberate read of the behaviour changes.
10. **`material3` is an alpha.** Pinned exactly, so it cannot move underneath a release, but
    it will need migrating when Expressive stabilises.
11. **Heed has not been ported to Keel.** The base was designed against one consumer, which
    is the condition under which a base grows things only one app needs.
