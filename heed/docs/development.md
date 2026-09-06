# Building and hacking on Heed

## Layout

```
data/       Room entities, DAO, settings, repository (owns the model + caches)
capture/    listener service, notification mapping, hold buffer, decision engine
score/      feature extraction, rules, online classifier, blending pipeline
digest/     summariser interface + template implementation, WorkManager job
export/     redaction levels, JSON document builder, share-sheet plumbing
usage/      foreground sessions from UsageStats, notification attribution, judging
focus/      scroll watcher, surface matching, focus sessions, the friction overlay
notify/     re-raising alerts, inline feedback action
ui/         Compose: onboarding, inbox, detail ("why"), settings, per-app rules
```

## Build

Needs JDK 17–21 (not 26 — AGP rejects it) and the Android SDK.

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
./gradlew testReleaseUnitTest    # 148 tests over features, rules, classifier, pipeline,
                                 # scrolling, sessions, groups and redaction
adb install -r --user 0 app/build/outputs/apk/release/app-release.apk
```

Then grant notification access: Settings → Notifications → Device & app notifications.

A debug build works for development, but do not leave one on a phone you use: it carries
the `DEBUGGABLE` flag, which lets anyone with adb read the notification database through
`run-as`. See [Signing](#signing-and-the-debug-build) below.

### The one grant that needs a computer

Everything Heed needs can be granted from inside the app except the grey screen, which
writes a secure setting. Android has no in-app flow for that by design:

```bash
adb shell pm grant io.github.sebastianyousef.heed android.permission.WRITE_SECURE_SETTINGS
```

It survives `adb install -r`, is dropped by a full uninstall, and is per Android user — a
copy of the app in another profile needs its own grant.

Install to the owner profile only, with `--user 0`. Without it `adb install` puts a copy in
*every* profile including a private space, which is what caused the banking-app confusion
described in [Design decisions](decisions.md).

## Signing and the debug build

Every build that reached the phone until now was a *debug* build, carrying the
`DEBUGGABLE` flag — which lets anyone with adb read the app's database through `run-as`.
For an app whose whole premise is that your notification history stays on the device,
that was the wrong thing to leave in place. Release builds are now signed from a keystore
kept out of the repository (`keystore.properties`, gitignored), so they install as
ordinary updates and carry no debug flag.

Losing that keystore means no future release can update an existing install in place, so
it is worth backing up.

## Cost

Heed is a background app that watches notifications and scrolling, so it has to be close
to free or it is not worth running. Measured on the test device, backgrounded with the
screen on and the accessibility service connected:

| | Before | After |
|---|---|---|
| CPU, backgrounded | ~7.5% of a core | **0.73%** |

Four things did it, in order of how much they mattered:

**The accessibility service was told about every app on the phone.**
`TYPE_WINDOW_CONTENT_CHANGED` fires constantly in every app, and each one is a binder
transaction whether or not it is wanted. `AccessibilityServiceInfo.packageNames` now names
only apps with a rule and known scrollers, so the system filters the rest at the source.
(A running focus session is the one exception: it drops the filter entirely, because it
turns apps away precisely on the grounds that no rule exists for them.)

**Every scroll event hit the database.** `maybeIntervene` launched a coroutine, ran a Room
query and read DataStore — per event, tens of times a second on a flick — almost always to
discover there was no rule. Rules and taught screens are now held in memory and refreshed
from a flow, so the common case is a hash lookup that allocates nothing.

**The foreground poller ran once a second regardless.** It now stops entirely while the
screen is off, drops to eight seconds when nothing is configured that needs to act the
moment an app opens, and prefers the window change the accessibility service already
received over asking `UsageStatsManager` again.

**The Attention screen loaded the whole corpus.** Two thousand notifications and two
thousand sessions, joined in Kotlin, on every database change. The same arithmetic is now
three `GROUP BY` queries that never materialise a row in the heap.

Two smaller ones: `warmCaches` was being called by three services and started a full set of
collectors each time, and app icons were rasterised inside composition on the main thread.
