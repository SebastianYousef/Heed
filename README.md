# Ply

**An Android training log built around one number: how many taps it costs to record a set
while you are standing in a gym.**

The answer is **one**. Everything else in the app is arranged to keep it there.

Two halves, in one app because between them there is always a reason to open it:

- **Training.** Log sets during a session, with a rest timer that survives the screen going
  off, a history you can read, and records that say which kind of record they are.
- **Movement.** A step counter that costs almost nothing to run, with today, the week's
  shape, and a home-screen widget.

Nothing leaves the device. The app **has no internet permission at all** — not as a promise
about the code, but as a thing the kernel enforces. See [Privacy](docs/privacy.md).

> **0.1.0.** It builds, 42 tests pass, and most of it has not yet been run on a phone.
> [Status](docs/status.md) is specific about which parts.

---

## What it does

### Training

- **One tap to log a set.** The weight and reps are already what you did last time, so the
  common case — repeating a set — is confirming and pressing Log. Adding weight is two taps.
  There is no keyboard anywhere on that screen. [The full count](docs/logging.md).
- **A rest timer in the notification shade**, not in the app. It starts itself when you log
  a set, counts down without waking the CPU, alerts on time through a doze, and keeps
  counting *upwards* afterwards — because thirty seconds later the useful question is not
  whether it finished but how long you have actually been standing there.
- **Records that say what they mean.** Heaviest ever, best estimated max, and heaviest at a
  given rep count are three different things that disagree constantly, so each is named and
  none is called *the* record.
- **Estimates that refuse.** Above twelve reps a one-rep-max estimate is measuring
  discomfort tolerance rather than strength, so Ply prints nothing instead of a number it
  cannot stand behind.
- **Volume counted two ways**, because there is no single right way — hard sets ignores load
  entirely, tonnage is dominated by whatever moves the most mass — and the app says which
  convention it used rather than printing one number called "volume".
- **876 exercises with instructions, offline**, from the public-domain
  [free-exercise-db](https://github.com/yuhonas/free-exercise-db), vendored into the APK.
  Searchable, filterable by muscle, and led by what you have actually been doing.

### Movement

- **Steps, without a permanent notification or a service that never dies.** The hardware
  counts with the CPU asleep; Ply reads the counter every fifteen minutes and writes down
  the difference. [How, and what it costs](docs/movement.md).
- **Reboots handled exactly.** The counter resets to zero at boot, which is where every
  pedometer's bugs live. Ply detects a restart on the elapsed clock — monotonic within a
  boot, zero after one — so there is no tolerance to tune and a clock correction cannot look
  like a reboot.
- **No distance and no calories.** Both would be a step count multiplied by a guess.

## Installing

Build it (below), then:

```bash
adb install -r --user 0 app/build/outputs/apk/release/app-release.apk
```

`--user 0` matters. Without it, `adb install` puts a copy in *every* profile including a
private space.

Ply asks for one runtime permission, and only when you first open the Movement half:
activity recognition, which is the only way Android will hand over the step count. Refusing
it costs the step counter and nothing else.

## Building

Needs **JDK 21** — AGP rejects 26 — and the Android SDK with platform 37.

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew assembleRelease
./gradlew testDebugUnitTest
```

Release builds are signed from a keystore kept out of the repository, so they install as
ordinary updates and carry no debug flag. A clone without that keystore still builds and
still runs the tests. More in [Building and hacking](docs/development.md).

## Keel

Ply is built on **Keel**, a small shared base in this repository: the Material 3 Expressive
theme, the progressive-disclosure components, one chart used at every scope, the
keyboard-free stepper, and day arithmetic. It also owns the build convention plugin that
carries the no-network guard — because a guard that holds in one of two apps is worse than
no guard, since it still reads as a policy.

It is a module rather than a copied folder so the day it moves into a repository of its own,
nothing but the settings file changes. [Heed](https://github.com/SebastianYousef/Heed) is
the intended second consumer and has not been ported yet.

## Read further

The rest lives in [`docs/`](docs/README.md):

- [The logging screen](docs/logging.md) — the tap count and where every tap went
- [Counting steps](docs/movement.md) — reboots, batching, and what a schedule costs
- [Privacy](docs/privacy.md) — no network, what enforces it, what is stored
- [Design decisions](docs/decisions.md) — including the four toolchain attempts that failed
- [Building and hacking](docs/development.md) — layout, versions, signing, tests
- [Status](docs/status.md) — verified, unverified, and the known gaps

## Licence

GPL-3.0-or-later. You are free to use, study, change and share it; if you distribute it or
anything built on it, that has to come with source under the same licence, and the copyright
notices stay on. Closed-source redistribution is not permitted.

Copyright © 2026 Sebastian Yousef. See [LICENSE](LICENSE).

## Author

Written and maintained by **Sebastian Yousef**
([@SebastianYousef](https://github.com/SebastianYousef)), the sole contributor.

## Credits

The exercise library is [free-exercise-db](https://github.com/yuhonas/free-exercise-db) by
Jonathan Yu, released into the public domain under the Unlicense. Ply vendors the exercise
data and instructions; the accompanying images are not included.
