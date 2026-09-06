# Privacy

Ply holds what you lift, what you weigh, and where you walked. This page says what happens
to it, and what enforces that rather than promises it.

## No network access, enforced by the kernel

Ply has **no `INTERNET` permission**. Android puts a process in the `inet` group only when
that permission is granted, so the kernel refuses the syscall. This is not a claim about
what the code does — there is no code path, no bug, and no dependency that can open a socket
from this app.

Three things hold it in place:

1. `INTERNET` and `ACCESS_NETWORK_STATE` are declared with `tools:node="remove"` in the
   manifest, so a library that declares one cannot pull it in through manifest merging.
2. A Gradle task reads the **merged** manifest of every variant and fails the build if
   either appears. It is attached to `assemble`, so no installable artifact can skip it.
3. That task lives in the shared build plugin rather than in this app, so the second app in
   the family cannot quietly have a weaker version of it.

The check is a build failure, not a warning. If network access is ever genuinely wanted,
that is a decision to argue for in [decisions.md](decisions.md) and to remove from this page
first — not a build error to silence.

**This is why the exercise library is vendored.** 876 exercises with their instructions ship
inside the APK at 800 KB. An app that downloads its exercise database is an app that needs
the permission, and everything above stops being true.

## No account, no analytics, no crash reporting

There is nothing to sign in to. No identifier is generated, no usage is measured, and no
crash is reported anywhere — none of which needs enforcing separately, because all of them
would need a socket.

## No backup, and that is deliberate

`allowBackup` is off and both cloud backup and device-to-device transfer are excluded.
Android's cloud backup would put a training history and a bodyweight series on Google's
servers, which is the one thing an app with no network permission has no business arranging
by proxy.

The consequence is real: **a factory reset or a new phone loses everything** unless it was
exported first. Export is a deliberate act inside the app.

## What is stored, and where

Everything is in one SQLite database in the app's private storage, readable only by this
app's UID:

| | |
|---|---|
| Sessions and sets | weight, reps, RPE, timestamps |
| Bodyweight and measurements | one entry per day |
| Steps | per hour, and the goal in force that day |
| Exercises | the vendored library, plus anything you add |
| Routines | your plans |

Nothing is encrypted beyond the device's own full-disk encryption. On a locked, encrypted
phone that is meaningful protection; against someone who has your unlocked phone it is not.

## Release builds only

Release builds are signed from a keystore kept out of the repository. A debug build carries
the `DEBUGGABLE` flag, which lets anyone with adb read the database through `run-as` — so a
debug build never goes on a phone that is actually used. Accrescent rejects both a debug
certificate and a debuggable manifest outright, which is the same rule stated from the other
direction.

## The permissions, and what refusing each one costs

| Permission | Why | Refusing it |
|---|---|---|
| `ACTIVITY_RECOGNITION` | Reads the hardware step counter | Movement half does nothing; Training half unaffected |
| `POST_NOTIFICATIONS` | The rest timer *is* a notification | The timer runs and can never tell you it finished |
| `VIBRATE` | The alert at zero | Silent alert |
| `RECEIVE_BOOT_COMPLETED` | The step counter resets at boot and must be re-read | Steps between the last read and a reboot smear |
| `FOREGROUND_SERVICE`, `..._SPECIAL_USE` | The rest timer survives the screen going off | — |
| `USE_EXACT_ALARM` | The alert fires on time through a doze | — |
| `WAKE_LOCK` | Pulled in by WorkManager | — |

That is the complete list. There is no location permission, no camera permission, no storage
permission, and no package-visibility query.
