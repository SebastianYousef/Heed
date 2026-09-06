# Status, and what is not verified

Ply is 0.1.0. It builds, its tests pass, and the parts below are honest about which of it
has met a real phone.

## Verified

- Release build produces a signed APK, 3.6 MB, with the exercise library inside it.
- The merged manifest contains **no** `INTERNET` and no `ACCESS_NETWORK_STATE`, confirmed by
  dumping permissions out of the built APK rather than by reading the source manifest.
- The no-network Gradle guard runs as part of `assemble` and reports zero findings.
- 54 unit tests pass over units, 1RM estimation, record detection, volume aggregation, plate
  loading, step reconciliation and the plate-inventory parser.
- **The whole vendored exercise library parses**, run through the real parser on the JVM:
  876 entries, every one with an id, a name and at least one primary muscle, no duplicate
  ids, no muscle outside the seventeen the picker filters on, and instructions intact on
  more than 850 of them. This was previously the first item under "not verified" — seeding
  happens once, on a phone, and its failure mode is an empty library that looks exactly like
  a broken app.
- The device has the hardware sensors this depends on: `android.sensor.step_counter` and
  `step_detector`, both `ACTIVITY_RECOGNITION`-gated, on a Pixel 10 running GrapheneOS
  (Android 17, SDK 37). A pre-existing pedometer was observed holding the counter with a
  one-hour batching period, so batched reads work on this hardware.

## Verified on the device

Installed as a signed release to the owner profile on the Pixel 10 (GrapheneOS, SDK 37)
and driven through by hand:

- First launch seeds the library and the picker is populated; search and muscle filters work.
- **`run-as` is refused — "package not debuggable"** — which is the release-build discipline
  holding from the outside.
- Logging a set costs one tap, and the steppers keep the last set's numbers for the next.
  Holding the plus for three seconds carried the weight from 0 to 197.5 kg, so the repeat
  and its acceleration work.
- Records fire correctly: set 1 of a new exercise announced nothing, and a heavier set 2
  announced all three by name.
- RPE opens on the row after the set and saves.
- The rest timer runs as a foreground service (`specialUse`), posts a silent ongoing
  `stopwatch` notification with two actions, and sets an `ELAPSED_WAKEUP` exact alarm tagged
  `REST_ELAPSED`. The countdown is visible in the shade as "Resting · 02:24".
- Activity recognition is requested from the Movement half, granted, and the step worker
  schedules; the ring and week strip render. The first reading credits nothing, by design.
- Data survived an update install.

## Not verified — needs more than a bench test

- The exact alarm firing **through a real doze**, which is the whole reason it is an alarm.
- A real reboot, and the steps lost either side of it.
- The widget on a home screen.
- The export share sheet reaching another app.
- Whether any step is ever actually counted — the phone sat on a desk throughout.

1. **The logging screen's tap count** is now a measurement rather than a claim, but only
   for the paths driven above.
   Also still unexercised on the device: the weekly volume card, the bodyweight control,
   the routine editor and the plan strip.
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

1. **No measurement entry.** The table and DAO method exist; bodyweight has a control and
   the tape-measure sites do not.
2. **No custom exercises.** `Exercise.customId` and the `custom` flag exist, the export
   writes them, and nothing can create one.
3. **No undo on deleting a set.** One tap on the row's cross and it is gone.
4. **No import.** Export writes a documented, versioned JSON document; nothing reads one
   back. Until that exists, an export is an archive rather than a migration.
5. **The hourly step shape is approximate.** Steps land in the hour they were read in, not
   walked in. See [movement.md](movement.md); the fix is a batched listener and the
   reconciler already accepts the shape it would produce.
6. **`targetSdk` is 36 while the device runs 37**, so Android 17 compatibility behaviours
   apply. Moving to 37 should be a deliberate read of the behaviour changes.
7. **`material3` is an alpha.** Pinned exactly, so it cannot move underneath a release, but
    it will need migrating when Expressive stabilises.
8. **Heed has not been ported to Keel.** The base was designed against one consumer, which
   is the condition under which a base grows things only one app needs — so Keel currently
   ships only what Ply calls, and porting Heed will add its meter and chart legend back
   from Heed's own source rather than from a guess made here.
