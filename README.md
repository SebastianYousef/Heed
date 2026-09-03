# Heed

An Android notification filter. It reads everything that arrives, decides what actually
needs you, and keeps the rest in an inbox with a periodic summary. The judgement is a
small model that runs on the phone and learns from what you open and what you swipe away.

Nothing leaves the device.

## The constraint everything else follows from

Android gives a third-party app no way to stop a notification before it makes noise.

`NotificationListenerService.onNotificationPosted()` fires *after* delivery — the sound
has played, the phone has buzzed, the heads-up banner is on screen. Cancelling there
still works, but the user has already been interrupted; they just watch the notification
vanish. That is the flash you see in apps that try this.

The API that would solve it, `NotificationAssistantService`, runs *before* display and can
demote a notification's importance so it never alerts. It is `@SystemApi`: not on the
public SDK classpath, so an ordinary app cannot compile against it, never mind be selected
as the device's assistant. This is not device-dependent — it is closed to third-party apps
everywhere. (Verified by compiling against `android.jar` for API 35: `Unresolved reference
'NotificationAssistantService'`.)

So Heed takes the other road: **make the sources silent and become the only thing on
the phone allowed to make noise.**

During onboarding you set your noisy apps to Silent in Android's own settings. They keep
arriving, they just stop interrupting. Heed sees all of them through the listener, and
re-raises — with its own sound, its own channel — only the ones worth your attention.

The side effect is the good part. Because nothing has alerted you, latency stops mattering.
Heed can hold a notification for a second or five, collect whatever else lands in that
window, and decide properly, instead of racing to cancel something that already went off.

## Pipeline

```
notification posted (silently, because the source app is muted)
        │
        ├─ ignorable?  ongoing / foreground service / no-clear / progress bar
        │              ambient category / media controls / no text     ──> untouched
        │
        ├─ live display?  a channel that rewrites itself constantly     ──> untouched
        │                 (step counter, download, navigation, timer)
        │
        ├─ rules ─────────────────────────────────────────────────────────────┐
        │    hard overrides: calls, alarms, one-time codes    ──> always alert │
        │                    per-app Always / Never            ──> forced      │
        │                    app posted it at IMPORTANCE_MIN   ──> always file │
        │    otherwise a prior in 0..1 from category, people, importance, ...  │
        │                                                                     │
        ├─ classifier ── hashed features ──> logistic regression ──> 0..1      │
        │                                                                     │
        └─ blend, weighted by how much the model has actually seen ────────────┘
                 score = (1 − confidence)·prior + confidence·model
                 confidence = examples / (examples + 60)
        │
        ├─ same key seen before?  update that row, never insert a second one
        │
        ├─ hold window (default 2s) — collapse bursts, drop transients
        │                              persisted as HELD before the wait starts
        │
        └─ score ≥ threshold ?
              yes ──> Heed raises its own alert, cancels the silent original
              no  ──> filed in the inbox, rolled into the next digest
```

A fresh install therefore behaves purely on rules, and the model fades in as you teach it.
Getting this wrong in the other direction — a coin-flip first week — is how an app like
this loses a user before it has learned anything.

## Why a linear model and not an LLM

Classification runs on the delivery path, on every notification, all day. The budget is
milliseconds and approximately zero battery. Hashed features plus logistic regression
trains online from a handful of examples, costs microseconds, and every weight maps to a
readable token or a named slot — which is what lets the detail screen show you *why*
something was filed. A model you cannot interrogate is one you stop trusting the first
time it is wrong.

Summarisation is the opposite shape: a few times a day, off the hot path, deferrable to
when the phone is charging. That is where an on-device LLM earns its keep, and
`Summarizer` is the seam for it — see the KDoc in `digest/Summarizer.kt` for wiring up
Gemma 3 1B through MediaPipe. The templated summariser it ships with is genuinely fine
until then, and free.

## Live displays

Some notifications are not events at all. A step counter posts one notification and then
rewrites it every few steps, all day; Android re-fires `onNotificationPosted` on every
rewrite. Download progress, navigation, media timers and sync indicators behave the same
way. Left alone, a single step counter generates thousands of "new" notifications a day.

Two mechanisms handle it. Most are caught by declaration — `FLAG_ONGOING_EVENT`,
`FLAG_FOREGROUND_SERVICE`, `FLAG_NO_CLEAR`, an ambient `category`, or a progress bar in
the extras. The rest are caught by behaviour: `LiveUpdateDetector` watches update rate,
and a channel that rewrites one notification five times inside two minutes is remembered
as a live display and skipped from then on. Detection is per (package, channel), so an
app's step counter can be ignored while its "goal reached" alert still gets through.
It is reversible from the per-app screen.

Ignored is not hidden. These notifications are left in the shade exactly as the app posted
them — Heed just does not judge, store or re-raise them. Your step count stays where you
expect to see it.

Separately, every notification is deduplicated by key: an app updating a notification in
place updates the row it already has, and identical reposts only bump a counter. Without
this a busy chat thread turns the inbox into a changelog.

## Staying alive

A `NotificationListenerService` can be unbound for reasons unrelated to the user — low
memory, an app update, a crash. The failure is silent: notifications simply stop arriving
while the app looks fine, and an empty inbox is indistinguishable from a quiet day. So
`requestRebind()` is called from `onListenerDisconnected`, a `ListenerWatchdogWorker`
re-checks every 30 minutes, and the inbox shows a red banner whenever the listener is down.

On AOSP-derived systems, GrapheneOS included, unbinding is rare. On skinned Android
(Samsung, Xiaomi) aggressive battery management makes it routine.

## Learning signal

| Event | Label | Weight |
|---|---|---|
| Tapped the notification | relevant | 1.0 |
| Swiped it away | noise | 0.4 |
| "I needed this" in the inbox | relevant | 3.0 |
| "Noise" in the inbox or the alert action | noise | 3.0 |

Swipes are deliberately weak — people clear notifications reflexively, and treating that
as a firm opinion teaches the model the wrong thing. Our own cancels are excluded
entirely; training on them would only teach the model to agree with itself.

## Layout

```
data/       Room entities, DAO, settings, repository (owns the model + caches)
capture/    listener service, notification mapping, hold buffer, decision engine
score/      feature extraction, rules, online classifier, blending pipeline
digest/     summariser interface + template implementation, WorkManager job
notify/     re-raising alerts, inline feedback action
ui/         Compose: onboarding, inbox, detail ("why"), settings, per-app rules
```

## Build

Needs JDK 17–21 (not 26 — AGP rejects it) and the Android SDK.

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # 14 tests over features, rules, classifier, pipeline
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then grant notification access: Settings → Notifications → Device & app notifications.

## Status

Built and unit-tested (24 tests); not yet run on a physical device. Worth checking first
on hardware:

- how long `cancelNotification` actually takes for an app you have *not* silenced, to see
  how bad the fallback path feels in practice
- whether the re-raised alert's `contentIntent` reliably opens the source app — it is held
  in memory only for the length of the hold window
- whether five updates in two minutes is the right live-display threshold for real apps

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
