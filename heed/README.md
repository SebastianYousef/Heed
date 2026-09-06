# Heed

**An Android app that decides what is worth interrupting you for, and shows you where your
attention actually went.**

Two halves, in one app because they answer each other:

- **Notifications.** Everything that arrives is read, judged and either raised or filed in
  an inbox with a periodic summary. The judgement is a small model that runs on the phone
  and learns from what you open and what you swipe away.
- **Attention.** Screen time, opens and scrolling, with limits you can actually aim: a
  budget on the *feed* rather than on the app, so you can message all day and still get
  five minutes of scrolling.

Because it saw both, it can tell you what an interruption cost:

> Instagram interrupted you 14 times this week. You opened 9. Those 9 became 3h 40m.
> That is about 24 minutes of you per notification.

Nothing leaves the device. The app **has no internet permission at all** — not as a
promise about the code, but as a thing the kernel enforces. See [Privacy](docs/privacy.md).

---

## What it does

### Notifications

- **Silences the source, and becomes the only thing allowed to make noise.** Android gives
  no way to stop a notification before it alerts you, so Heed asks you to mute the noisy
  apps in Android's own settings and re-raises only the ones worth it. The side effect is
  the good part: since nothing has gone off, it can wait a second or two, collect the rest
  of the burst, and decide properly.
- **Learns from you, and tells you why.** Every decision has a readable reason — which
  rule fired, which words moved the score, which of your own corrections it is applying.
- **Knows the difference between a thread and a person.** A group chat can be noise while
  one person in it is not, and both are held as one-way hashes rather than names.
- **Leaves live displays alone.** Step counters, downloads, navigation and timers stay in
  your shade untouched, rather than becoming thousands of "new" notifications a day.
- **Clear several at once.** Hold one to start picking, tap the rest, delete them together,
  with one undo.
- **Never guesses about the important ones.** Calls, alarms and one-time codes always
  alert, whatever the model thinks.

### Attention

- **Screen time you can read.** A week of bars with hour gridlines, tap a day to see the
  apps in it, tap an app for its own week.
- **Limits per app** — minutes a day, opens a day, or a scrolling budget that leaves
  messages open.
- **Shared budgets across a group of apps.** Half an hour each of three feeds is an hour
  and a half of the same habit, and three separate limits all report success. A group
  gives them one budget between them — with its own week, a breakdown of which member
  spent it, and a colour, so the chart can say which *habit* Tuesday was made of.
- **Break the feed.** A pause every N scrolls that takes nothing away and hands the feed
  straight back. An infinite feed works by never presenting a last post, so carrying on is
  never a decision — this manufactures the moment where it is.
- **Block a feed without blocking the app.** Snapchat's Spotlight and Discover, Instagram
  Reels and Explore, YouTube Shorts, Reddit's short feed — your conversations untouched.
  You can teach it another screen in two taps.
- **Focus sessions.** Everything closes except what you allowed. Starting is one tap and
  stopping takes ninety seconds, because the person who set the session and the person who
  wants out of it are not the same person.
- **Bedtime**, and a **grey screen** for the apps that are built on colour.
- **An About screen** with the version, build and storage format — the four numbers any
  account of a problem needs.
- **A home-screen widget** with today's screen time, how much of it was scrolling, and how
  many notifications Heed absorbed.

## Installing

Build it (see below) or install the release APK, then walk through onboarding. Everything
Heed needs is granted from inside the app, each behind a system screen it links you to:

| Grant | Without it |
|---|---|
| Notification access | The filter does nothing at all |
| Usage access | No screen time, no time limits, no open counts |
| Screen access (accessibility) | No scroll rules, no feed blocking, no seam |
| Draw over other apps | Blocks and pauses cannot be shown |
| Post notifications | Heed can file notifications but never raise one |

**One optional grant needs a computer.** The grey screen writes a secure setting, which
Android has no in-app flow for:

```bash
adb shell pm grant io.github.sebastianyousef.heed android.permission.WRITE_SECURE_SETTINGS
```

Skip it and everything else still works; the grey-screen switch stays disabled and says
why rather than pretending.

Install to the owner profile only:

```bash
adb install -r --user 0 app/build/outputs/apk/release/app-release.apk
```

Plain `adb install` puts a copy in *every* profile including a private space, and an
accessibility service is enabled per profile — which is the whole cause of a
banking-app problem Heed once shipped a workaround for. See
[Design decisions](docs/decisions.md).

## Building

Needs JDK 17–21 (not 26 — AGP rejects it) and the Android SDK.

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew assembleRelease
./gradlew testReleaseUnitTest    # 148 tests: features, rules, classifier, pipeline,
                                 # scrolling, sessions, groups, redaction
```

Release builds are signed from a keystore kept out of the repository, so they install as
ordinary updates and carry no debug flag. More in [Building and hacking](docs/development.md).

## Read further

The rest lives in [`docs/`](docs/README.md):

- [How the filtering works](docs/how-it-works.md) — the pipeline, the model, and why it is
  a linear one
- [Attention, limits and focus](docs/attention.md) — every rule and what it is for
- [Feed detection](docs/feed-detection.md) — telling a feed from a conversation
- [Privacy](docs/privacy.md) — no network, what is stored, what is exported
- [Design decisions](docs/decisions.md) — including the ones that were reversed
- [Building and hacking](docs/development.md) — source layout, signing, what it costs to run
- [Status](docs/status.md) — verified, unverified, and known gaps

## Licence

GPL-3.0-or-later. You are free to use, study, change and share it; if you distribute it or
anything built on it, that has to come with source under the same licence, and the
copyright notices stay on. Closed-source redistribution is not permitted.

Copyright © 2026 Sebastian Yousef. See [LICENSE](../LICENSE).

## Author

Written and maintained by **Sebastian Yousef** ([@SebastianYousef](https://github.com/SebastianYousef)),
the sole contributor to this project.

## Credits

Snapchat's and Instagram's view identifiers were read off a running device with
`uiautomator` and cross-checked against the list in
[Mindful](https://github.com/akaMrNagar/Mindful), whose menu also shaped how Heed's own
Focus screen is organised. No code was taken from it.
