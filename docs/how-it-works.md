# How the filtering works

Everything on this page is about the **Notifications** half of Heed: what arrives, what
gets through, and how the judgement is made. For time and scrolling, see
[Attention and limits](attention.md).

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

## Who it is from, and when

"Is this from WhatsApp" is a much weaker question than "is this from the person I always
reply to". WhatsApp is both your partner and the flat's bin-day group, and an app-level
filter cannot separate them — which is how these systems end up either interrupting for
everything or burying the one message that mattered.

So a notification carries **two** identities, because "which thread" and "who in it" are
different questions and one field can only ever answer one of them.

The thread is a shortcut id where the app provides one, otherwise the conversation title,
otherwise — only for `CATEGORY_MESSAGE` — the notification title. The person is the sender
of the newest `MessagingStyle` message. Both are hashed into their own block of the
feature vector, so the model can hold "this group is noise" and "this person is not" at
the same time and let the two weights settle it.

That was a bug, not a refinement. The sender used to sit *second* in the thread's chain —
above the conversation title — so a group chat in an app that sets no shortcut id resolved
to whoever happened to speak last. The flat's bin-day group had a different identity every
time a different person posted in it, so it could never be learned at all, and neither
could the one person in it you never want to miss. `PersonFeatureTest` pins the fix: same
group, same words, opposite labels, and the model separates them — which is only possible
because the person carries its own weight.

What is stored either way is a **hash, never the name**. It is stable, so the model can learn a thread over months; one-way, so
the database gains no new readable record of who you talk to; and it outlives the
retention scrub that clears the text, so what has been learned survives what can be read.

Time is modelled per sender rather than globally. A standup bot at nine and the same bot
at midnight are the same sender and not the same event, and a single engagement rate
averages that away — so history is bucketed into four-hour slices, coarse enough to fill
within a week of ordinary use.

Two properties worth stating. Only feedback the user actually gave counts towards a
sender's engagement; a notification they never touched says nothing either way, and
treating silence as rejection would bury every thread they read on the lock screen. And a
sender never seen before scores neutral, not negative — a first message from someone new
is judged on its content, because doing otherwise filters exactly the messages that most
need to arrive.

The feature block was **appended** to the vector rather than inserted, and the loader now
grows a shorter stored vector instead of discarding it. Every weight learned before this
existed keeps its index and its meaning; the alternative silently wiped a user's training
on an ordinary upgrade.

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

## The inbox

Three tabs — Needed, Filtered, Everything — which are a pager, so they swipe sideways as
well as tapping. Tapping a notification opens the detail screen, which shows the score,
the rule that fired, the features that moved it, and the two buttons that teach the model.

**Clearing several at once.** Hold a notification to start picking, tap the rest, and the
bin deletes them together with one *Undo* in the snackbar. The gesture is a long press
rather than a swipe, and that is a deliberate trade rather than an oversight: the tabs are
a pager, so a horizontal drag on a card is already how you move between them, and a
swipe-to-delete would take that gesture from the pager and quietly break tab swiping. It
also deletes one row per gesture, where the thing actually wanted is clearing out a
handful.

Undo lives as long as the snackbar and no longer. The deleted rows are held in memory and
put back with their original ids; they are not flagged as deleted in the database, because
a deletion that leaves the text on disk is not the thing the button says it is — and the
usual reason to delete a notification here is precisely that it should not be on disk.
