# Heed

An Android notification filter. It reads everything that arrives, decides what actually
needs you, and keeps the rest in an inbox with a periodic summary. The judgement is a
small model that runs on the phone and learns from what you open and what you swipe away.

Nothing leaves the device.

## It cannot talk to the internet

Heed reads every notification you receive, watches which apps you use, and — if you turn
it on — observes scrolling. That is a great deal of trust to ask for. What makes it
reasonable to give is that **the app has no `INTERNET` permission**, so it cannot open a
socket at all.

This is not a promise about what the code does. Android puts a process in the `inet`
group only when the permission is granted, so the kernel refuses the syscall. There is no
bug, no code path and no compromised dependency that can send your data anywhere, because
there is nothing to send it over.

Because *absent by accident* is not the same as *absent by design*, the permission is
explicitly removed with `tools:node="remove"` so manifest merging cannot reintroduce it,
and `app/build.gradle.kts` fails the build if `INTERNET` or `ACCESS_NETWORK_STATE` ever
appears in the merged manifest. That guard is itself verified: reintroduce the permission
and the build stops with an error naming it.

The full requested-permission list on a real install is:

```
POST_NOTIFICATIONS          raise the alerts it decides you need
RECEIVE_BOOT_COMPLETED      resume after a reboot
WAKE_LOCK, FOREGROUND_SERVICE   pulled in by WorkManager
```

`QUERY_ALL_PACKAGES` was dropped after testing showed a notification listener already gets
implicit package visibility for apps that post notifications. Everything else — notification
access, usage access, the accessibility service — is a special grant you make individually
and can revoke individually.

The one way data leaves is the export, which you trigger by hand and hand to a share
target of your choosing.

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

## Forgetting

Retention runs in two stages, because "delete my history" and "stop anyone reading my
history" are different requirements and only one of them costs you anything.

After the **content window** (default 7 days) the text is scrubbed but the row remains:
which app, when, what Heed decided, why, and what you told it. The shape of the text —
length, word count, whether it held a link or looked like a one-time code — is recorded on
the way out, so a scrubbed row still explains itself and still contributes to statistics
and exports. After the longer **record window** (default 90 days) the row goes too.

Neither stage costs the classifier anything, and this is by construction rather than by
luck. Training happens the instant you react to a notification and is folded straight into
the weights, which live in their own row. The text was never what the model was carrying —
it was only ever evidence you might want to reread. `RetentionTest` asserts this directly:
train a model, scrub every row it learned from, and the serialised weights must be
byte-identical and the predictions unchanged.

## Exporting your data

Settings → Export your data writes a JSON file and opens the share sheet. Three levels:

| Level | Contains | Safe to send to someone else |
|---|---|---|
| Stats only | Counts, distributions, per-app totals, learned weights. No row-level data. | Yes |
| Redacted *(default)* | One row per notification, with all text replaced by its shape. | Yes |
| Everything | Full notification text, including messages and one-time codes. | **No** |

The redacted level keeps what is needed to explain a decision — app, category, score,
which rule fired, the feedback you gave — and replaces the words with measurements:
length, word count, digit groups, whether it held a link, whether it looked like a
one-time code. Those are the same signals the classifier sees, which is why they are
enough to work out why something was scored the way it was, and none of them can be
inverted back into the original text.

Two things that are less obvious and are handled anyway. Notification **channel ids** are
hashed rather than exported, because a few apps mint one per conversation and embed a
phone number or account id in it. **Digest summaries** are withheld below the full level,
because a summary quotes the notifications it summarises. Notification keys are never
exported at all, for the same reason as channel ids.

This is enforced by test, not by intent: `ExportRedactionTest` plants a distinctive string
in every text field, the channel id and the digest summary, then asserts none of them
appear anywhere in the output — plus an inverse test that the full export *does* contain
them, so the check cannot pass by emitting nothing. `ScoreReasonTest` covers the one
field that is passed through verbatim, asserting the reason string never quotes the
notification.

Files are written to the cache directory, capped at the three most recent, and handed out
only as a per-Intent grant through a non-exported `FileProvider`.

## Two halves

The app is split in two, because "what reaches me" and "where does my time go" are
different questions and one list made both harder to think about.

**Notifications** is the filter, the inbox and the digest. **Attention** is sessions,
scrolling, and the rules about them. They share a database and feed each other — see the
`CLICKED_THEN_SCROLLED` signal below — but they are answered separately.

## Attention: what an interruption actually costs

Screen-time apps can tell you that you spent forty minutes in an app. They cannot tell
you *why you opened it*, because they never saw the notification. Heed saw both, so it can
join them:

> Instagram interrupted you 14 times this week. You opened 9. Those 9 became 3h 40m.
> That is about 24 minutes of you per notification.

That join is the point of putting the two halves in one app, and it closes a loop that was
already open. Until now a tap trained the classifier as `CLICKED = relevant, +1`. That is
the obvious mistake in a system like this: **bait works precisely by being tapped.** When
the session that followed a tap turns out to be doom scrolling, the notification is
recorded as `CLICKED_THEN_SCROLLED` and trains as a negative instead. The filtering gets
better because the attention tracking exists.

## Banking apps, and why enforcement is split in two

Nordea, BankID, Swish and most of their peers refuse to start while *any* accessibility
service is enabled. That is a reasonable defence — accessibility is the standard vector
for overlay-and-tap account takeover — and it is not something to detect around or evade.

The first version put every limit behind the accessibility service, which meant it was
really asking people to choose between their bank and their screen-time rules. Nobody
makes that choice twice; they disable the app, which is exactly what happened here.

So enforcement is now two engines:

| | Needs | Does |
|---|---|---|
| `AttentionService` | usage statistics | screen time, time limits, launch limits, bedtime, grayscale |
| `ScrollWatcherService` | accessibility | scroll measurement, per-surface blocking |

Everything in the first row works with accessibility switched off, so the default install
is compatible with every banking app on the phone. Turning screen access on adds scroll
measurement and the ability to tell one feed from another, and the app says plainly that
it will break banking apps before you do it — with a one-tap "turn off for banking" that
calls `disableSelf()`, because hunting through system settings at a checkout is not a
thing anyone should have to do.

## Grayscale

Colour is the cheapest thing an app buys attention with, and a feed built on thumbnails
stops working in monochrome. Unlike a block screen there is nothing to argue with: the
phone still works, it is simply boring.

Android has exactly one way to do this without root — the display daltonizer in mode 0
(`MONOCHROMACY`). It is a secure setting, so it needs `WRITE_SECURE_SETTINGS`, which no
prompt can grant and which has to be given once over adb:

```
adb shell pm grant io.github.sebastianyousef.heed android.permission.WRITE_SECURE_SETTINGS
```

That is a real cost and it is worth saying why it is paid. An overlay cannot desaturate
what is underneath it; it can only tint. Every grayscale app that does not ask for this
permission is drawing a grey film over the screen, which dims it without removing a single
colour cue. Heed writes exactly two keys with the permission, both listed in
`focus/Grayscale.kt`, and still holds no network permission to send anything anywhere.

Heed also only ever undoes grayscale it turned on itself, so someone who keeps their phone
permanently grey does not find it back in colour because they opened LinkedIn.

### Telling one screen from another

Behavioural detection cannot distinguish Snapchat's Spotlight from Snapchat's chat list,
because both are simply scrolling. This is not a subtlety — it was a bug with teeth. A
`Block` rule on Snapchat in Automatic mode fired on the first few scrolls of *whatever was
on screen*, which in practice meant being thrown out of a conversation with a friend
mid-sentence.

The rule now: **a scroll count may only ever block in Automatic mode.** In Precise mode
the decision belongs entirely to surface matching, which knows which screen you are on, so
chats and friends' stories are untouchable by construction rather than by tuning. Apps
Heed ships anchors for start in Precise, and an existing Block rule on such an app is
migrated to Precise on upgrade — a rule that cannot do what its own description promises is
not a preference worth preserving.

Rather than hardcoding view ids per app — which breaks on every redesign and only covers
apps someone remembered — Heed learns them. You open the screen, press **Teach a screen**,
and it records a fingerprint. Do it once for Discovery and again for friends' stories,
mark the first Blocked and the second Allowed, and the carve-out works.

The fingerprint is **view identifiers and class names only**: the structural skeleton of
the layout. `SurfaceCapture` never reads `text` or `contentDescription` from any node, so
it recognises the shape of a feed without learning a word that is on it. Matching is
Jaccard overlap at 0.6, because real screens shift between visits and exact matching is
useless. Trees are only walked for apps explicitly set to Precise.

Where one view id uniquely names a screen, Heed uses that instead of a whole-layout
fingerprint: exact, cheap, and far more tolerant of redesigns, since a layout can be
rearranged around an element that keeps its id. `KnownSurfaces` ships anchors for
Snapchat Spotlight and Discover, Instagram Reels and Explore, YouTube Shorts and Reddit's
short feed, installed automatically when you switch an app to Precise. An anchor beats a
taught fingerprint, and an explicit allow beats a block, so exceptions still work.

Those identifiers are facts about other apps' layouts — `uiautomator dump` prints them —
and were cross-checked against the list in the GPL-2.0 [Mindful](https://github.com/akaMrNagar/Mindful)
project. No code was taken from it; Heed is MIT (see LICENSE) and copying GPL-2.0 source
would have forced a licence change.

### Apps Heed will never block

`CriticalApps` refuses to block authenticators, diallers, alarms, password managers and
settings, whatever rule is set on them and regardless of bedtime. A focus app standing
between you and a one-time code at 3am has stopped being useful and become a hazard, and
the rule that does it is almost never one you meant to set — this exists because a stray
Block rule landed on an authenticator during testing. Matching is by package and by
keyword, because the long tail of authenticator and banking apps cannot be enumerated and
erring the other way is much worse.

### Rules: budget the scrolling, not the app

Every app in this category limits *time in an app*, which forces a choice nobody wants:
block LinkedIn and lose your messages, or allow it and lose your evening. Heed budgets the
scrolling separately, so you can message all day and still only get five minutes of feed.

Three modes per app, plus two independent daily budgets:

| Setting | Effect |
|---|---|
| Measure | Records, never intervenes. |
| Nudge | Friction after the global unbroken-scroll threshold. |
| Block | Stops you after N scroll events in one burst. Small N is effectively instant. |
| Scrolling budget | Minutes of scrolling per day. The rest of the app stays open. |
| Time limit | Minutes in the app per day, checked on open before you scroll at all. |

**An honest limitation.** Blocking one *surface* — Snapchat's Spotlight but not its chats —
requires reading the screen to know which surface you are on, and Heed deliberately cannot.
`Block` with a tight scroll budget is the approximation: a feed is continuous scrolling and
a chat list is not, so 3-5 events stops Spotlight within a flick or two while opening a
conversation and reading it passes through. It is not surgical. Making it surgical would
mean granting content access, which would undo the main reason to trust this app.

### Detecting the behaviour, not the screen

Doom scrolling is not a place you go, it is a thing you do: long, fast, unbroken scrolling
you did not set out to do. So Heed measures that, rather than trying to recognise
particular feeds.

The accessibility service is declared **without `canRetrieveWindowContent`**, which means
Android will not hand it the text on your screen under any circumstances — not messages,
not what you type, not passwords. It receives two event types, "something scrolled" and
"the foreground window changed", and nothing else. Recognising Reels or Shorts by their
view ids would need that content access, would break with every redesign, and would only
ever cover apps someone remembered to list. Measuring the behaviour needs none of it and
works in an app nobody has heard of yet.

A session counts as scrolling when it clears **both** a sustained rate (25 scroll events
per minute) and one unbroken stretch (60s). Either alone is a false positive: a high rate
on its own is hunting through a list, and a long burst on its own is one flick down a long
article. Pausing to read breaks the stretch, so this measures the trance, not the time.

The intervention is friction, not a wall — a five-second delay and one honest sentence
about how you got there, drawn with `TYPE_ACCESSIBILITY_OVERLAY` so it needs no
`SYSTEM_ALERT_WINDOW` permission. Apps that hard-block get uninstalled by Friday.

## The widget

Today's screen time, the minutes of it that were scrolling, and how many notifications
Heed absorbed. On the home screen rather than inside the app, because a screen-time figure
you have to open an app to see is one you look at after the evening is already gone; on the
home screen it is in the way of the thing you were about to open, which is the only moment
it can change anything.

## Layout

```
data/       Room entities, DAO, settings, repository (owns the model + caches)
capture/    listener service, notification mapping, hold buffer, decision engine
score/      feature extraction, rules, online classifier, blending pipeline
digest/     summariser interface + template implementation, WorkManager job
export/     redaction levels, JSON document builder, share-sheet plumbing
usage/      foreground sessions from UsageStats, notification attribution, judging
focus/      scroll watcher (no content access), the friction overlay
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
7. **Snapchat's anchors are unverified on a live account.** The view ids in
   `KnownSurfaces` are cross-checked against public inspection of the app, but Precise
   mode has never been watched blocking Spotlight in practice. "Teach a screen" is the
   fallback and works regardless; the anchors are a convenience that may need a redress
   after a Snapchat redesign.
8. The grayscale filter is verified as *writable* (`WRITE_SECURE_SETTINGS` granted, keys
   confirmed present on the device) but the visual effect cannot be checked over adb —
   `screencap` reads upstream of the display colour transform, so a grey screen and a
   colour one produce byte-identical screenshots.
