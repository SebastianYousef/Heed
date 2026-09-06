# Status, and what is not verified

## Where Heed sits in this repository

Heed lives in the Keel repository alongside Ply, but is **not yet built on the `:keel`
base**. It still carries its own copies of the theme, the chart and the disclosure
components, and its own Gradle wrapper and settings file to build them with — Heed is on
AGP 8.7.3 and Gradle 8.9, while `:keel` and Ply are on AGP 9.4.0 and Gradle 9.7.1, and
those cannot share one Gradle invocation.

So the tree currently holds two builds. Run Heed's from `heed/`, not from the root. The
port that collapses them into one is the largest outstanding piece of work on this app, and
it is a toolchain upgrade before it is anything else.

`versionName` was walked back from `1.5.1` to `0.5.1` when the repository was restructured.
Nothing about the app changed; the 1.0 was a claim about how settled it was that turned out
not to be true four days in. `versionCode` cannot decrease and stays at 26.


Verified on a Pixel 10 running GrapheneOS/Android 17 (SDK 37), against real notifications:

- capture, scoring, decision and cancellation all work end to end
- the one-time-code override fires (`Your verification code is 448210` -> score 1.0, alerted)
- marketing language is caught (`50 percent off ... unsubscribe` -> 0.2, filed)
- dedupe holds: four identical reposts collapse to one row with `updateCount = 4`
- live-display detection fires on the fifth update inside the window and clears the rows
  it had already taken from that channel
- a real pedometer (`org.secuso.privacyfriendlyactivitytracker`, flags
  `NO_CLEAR|FOREGROUND_SERVICE`) is ignored entirely: zero rows, not even an app policy

Still unverified, and needing a phone in hand rather than adb:

- how long `cancelNotification` actually takes for an app you have *not* silenced, so how
  bad the fallback path feels in practice
- whether the re-raised alert's `contentIntent` reliably opens the source app — it is held
  in memory only for the length of the hold window
- the export share sheet end to end (the redaction itself is covered by tests)
- `targetSdk` is 35 while the test device runs SDK 37, so compatibility behaviours apply

Known gaps, in rough priority order:

1. **The learning loop only sees what it shows you.** Suppressed notifications rarely get
   feedback, so the model trains disproportionately on things it already decided to alert
   on and reinforces its own priors. Needs deliberate exploration (occasionally let a
   borderline one through) plus one-tap correction in the digest, which is currently
   read-only.
2. **"Alerted you and you ignored it" produces no signal.** Only taps and swipes train the
   model; a notification you were interrupted by and then left sitting for six hours is a
   strong negative that is currently discarded.
3. **Scores are not calibrated.** 0.55 does not mean "55% likely to matter", so tuning the
   threshold is guesswork. Worth fitting a Platt or isotonic calibration once there are a
   few hundred labels.
4. No inline reply from the inbox; `RemoteInput` actions are dropped rather than preserved.
5. Notification text is stored unencrypted. 30 days of `bigText` is a sensitive corpus.
6. `QUERY_ALL_PACKAGES` is only used for app labels and would need removing before Play.
7. **The anchors are correct for Snapchat 14.20.0.50 and will rot.** They were read off a
   running device and verified blocking Spotlight, but ids change with redesigns. The
   failure mode is benign — a stale anchor stops matching and nothing is blocked — and
   "Teach a screen" is the fix that does not need a new release.
8. **The Discover guard is only verified in one direction.** Friends' stories visible was
   confirmed not to block. That scrolling them away *does* start blocking follows from
   `RecyclerView` recycling the off-screen cards, but was not observed directly.
9. The grayscale filter is verified as *writable* (`WRITE_SECURE_SETTINGS` granted, keys
   confirmed present on the device) but the visual effect cannot be checked over adb —
   `screencap` reads upstream of the display colour transform, so a grey screen and a
   colour one produce byte-identical screenshots.
