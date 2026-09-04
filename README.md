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

## Forgetting

Retention runs in two stages, because "delete my history" and "stop anyone reading my
history" are different requirements and only one of them costs you anything.

After the **content window** (default 7 days) the text is scrubbed but the row remains:
which app, when, what Heed decided, why, and what you told it. The shape of the text —
length, word count, whether it held a link or looked like a one-time code — is recorded on
the way out, so a scrubbed row still explains itself and still contributes to statistics
and exports. After the longer **record window** (default 90 days) the row goes too.

Separately from the schedule, **any single notification can be erased on the spot** — the
bin icon on its detail screen. Retention is a promise about next week, which is not much
comfort in the hour after something arrives, and the fact that a notification came at all
can be the sensitive part rather than its text. The row goes entirely: inbox, statistics
and every future export.

The dialog is explicit that this is not an unlearn. If you had already marked the
notification, that lesson went into the weights when you gave it, and the weights carry no
link back to the row that produced them. Deleting is honest about the record; claiming
more would be the kind of privacy promise that sounds better than it holds.

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
scrolling, and the rules about them. **Focus** is a third thing again — a stretch of time
you decided in advance, rather than a limit reacting to one app. They share a database and feed each other — see the
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

## Banking apps, and a workaround for a problem that was not there

Enforcement is split in two, and that split is worth keeping whatever banks do:

| | Needs | Does |
|---|---|---|
| `AttentionService` | usage statistics | screen time, time limits, launch limits, bedtime, grayscale |
| `ScrollWatcherService` | accessibility | scroll measurement, per-surface blocking, the seam |

Everything in the first row works with accessibility switched off, which is worth having
on its own: it means the app degrades to something useful rather than to nothing.

Built on top of that split was a whole subsystem — a list of banking packages, a
foreground watcher, a notification offering to switch screen access off, and a setting to
do it automatically — resting on the belief that Nordea, BankID, Swish and Revolut refuse
to start while any accessibility service is enabled.

**The belief was wrong about the cause, and the subsystem has been deleted.**

### A private space is a separate user

This was the whole explanation, and the section below used to present it as one factor
among several. It is not; it is the entire thing.

**Accessibility services are enabled per Android user.** On the test device:

| | owner profile | private space |
|---|---|---|
| Mindful | ✓ | — |
| Nordea, BankID, Swish, Avanza | — | ✓ |
| Revolut | ✓ | — |

Mindful's service has never been in the same profile as the four banks that appeared to
object to it. Heed's was, because `adb install` without `--user 0` puts a copy in *every*
profile, including the private space — so the copy living beside the banks was the one
they were reacting to. Install with `--user 0` and they all start normally with screen
access on. Verified on the device.

Revolut, in the owner profile alongside both apps, never objected at all. It went into the
package list on an assumption, and what the previous version of this file called "verified
on the device" verified only that *Heed's own step-aside fired* — never that Revolut
refused anything.

Reading Mindful's source settles it from the other direction. Its service declares
`canRetrieveWindowContent`, `flagRetrieveInteractiveWindows`, enhanced web accessibility
and `feedbackAllMask` — strictly more capability than Heed asks for — with an entirely
ordinary manifest declaration and no `isAccessibilityTool`. Searching its Android sources
for `bank`, `wallet`, `finance` and `disableSelf` returns nothing. It has never contained
a line of code about this. There was no technique to copy, because there was nothing being
done.

### The better reason it is gone

Even if a bank had genuinely objected, the mechanism was the wrong answer.

A button inside a blocking app that switches off the thing doing the blocking is not a
concession to banks. It is a one-tap way out of every rule you have set — and it lived in
the notification shade, needing no password, no waiting and no explanation. An app whose
entire premise is that the version of you who set the rule should outrank the version who
wants out of it has no business shipping that button, whatever it is labelled.

Screen access can still be switched off. It is in system settings, where turning something
off costs the deliberate walk that it should.

### Never disable something you cannot re-enable

The lesson that produced the narrow keyword list is still the right lesson, and it now
applies to the whole idea rather than to its tuning.

The first version switched screen access off automatically whenever it saw a banking app.
The keyword list included "wallet". A crypto wallet on the test device matched it, the
accessibility service disabled itself, and — because Android does not let an app
re-enable its own accessibility service — every block stopped working permanently, with
nothing on screen to explain why. It presented as "the Spotlight block is inconsistent",
and it was: it worked until the first time a wallet was opened, then never again.

The response at the time was to narrow the list and to offer rather than act. That was an
improvement to a mechanism that should not have existed, and the failure recurred in a
gentler form months later: screen access went off, every scroll rule went quiet, the app
carried on displaying all of them as set, and it presented as "the Snapchat filter got
worse". The fix that mattered was not a better list. It was **deleting the one-way door,
and adding a banner that refuses to let its absence go unmentioned** — see the disconnected
banners, which now cover screen access as well as the notification listener.

An irreversible action taken on a guess needs consent. An irreversible action taken on a
guess that also defeats the point of the app needs deleting.

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

That claim needed two fixes to actually hold, both found by looking at a phone where the
filter is toggled by hand. Ownership was a `@Volatile` field, so it was lost whenever the
process died — and a bedtime rule holds the filter across exactly the hours in which a
background service is most likely to be killed, which meant Heed would come back and never
release a screen it had greyed. And ownership was never released when the *user* turned
the filter off mid-rule: the flag stayed claimed forever, so the next time they greyed
their own screen, the next rule to end turned it off underneath them. Ownership is now a
claim over a filter that is actually on, kept on disk, and released the moment it is not.

Heed writes the mode key only on the way *on*, never on the way off, so a monochromacy
setting you chose yourself survives Heed using it.

### Telling one screen from another

The identifiers below were read off a running device with `uiautomator`, not copied from
another project's list. That distinction turned out to matter: the previously shipped
`spotlight_card_static_thumbnail`, taken from Mindful, **does not exist in Snapchat
14.20.0.50 at all**, so the Spotlight block it was supposed to drive could never have
fired. What Snapchat renders now:

| Screen | Identifier | Note |
|---|---|---|
| Spotlight | `spotlight_container` | fills the window; unambiguous |
| Discover | `df_large_story` | guarded, see below |
| Friends' stories | `friend_card_frame` | never blocked |
| Chats | `ff_item` | never blocked |

Discover needed more than presence. Snapchat's Community tab is a single scrolling list
with your friends' stories along the top and the Discover feed underneath, so
`df_large_story` is in the tree from the moment the tab opens — while you are still
looking at your friends. So an anchor can carry an `unless`: another id whose presence
vetoes it. Discover blocks only when `friend_card_frame` has scrolled away, which is
exactly the line to draw.

Presence turned out to be the wrong test entirely. Everything on the Community tab is in
the node tree all of the time — friends' cards are still there after you scroll past them,
and Discover's cards are there before you reach them — so "does this id exist" answers
neither question. Anchors are now matched on **visible bounds**: a card scrolled off the
top has a negative bottom edge, and an anchor can require a real share of the viewport
before it counts as what you are looking at. Discover asks for 55% of the screen; the
friends' guard releases once their row falls below 8%.

The other bug was a flag. A surface block set `interventionShown`, which is cleared only
when the *package* changes — so after Spotlight was blocked once, nothing else in Snapchat
could be blocked until the app was left entirely. It was not intermittent; it was working
exactly once per visit. Surface blocks now carry their own short cooldown.

Verified end to end on the device, with Snapchat set to Block:

- Spotlight — bounces four times out of four (`ty=ACCESSIBILITY_OVERLAY` confirmed)
- Chat list, scrolled repeatedly — no overlay, stays put
- Community with friends visible — no overlay, despite `df_large_story` being present
- Opening a wallet no longer disables screen access

`spotlight_container` also hosts the full-screen viewer for Discover stories — found by
opening one and looking — so a single anchor covers both ways into the recommendations.

Leaving a blocked screen presses Back, twice at most, and **never Home**. The first
version fell back to Home, which threw the user out of Snapchat entirely: the exact thing
the feature exists to avoid, and a worse outcome than the feed it was preventing.

Blocking uses `GLOBAL_ACTION_BACK`, not `GLOBAL_ACTION_HOME`. You opened Snapchat to
message someone; throwing you out of the whole app to stop you seeing a feed punishes the
thing you actually came for. Back drops you out of the feed and leaves you where you were.

### Friends' stories: checked, not assumed

The obvious worry with a shipped anchor is that it catches something it should not. It was
worth opening one friend's story and looking, and the answer is on the device:

| | `spotlight_container` | `base_image_layer_container` | `df_large_story` visible |
|---|---|---|---|
| Spotlight | ✓ | ✓ | — |
| Discover story | ✓ | ✓ | — |
| **A friend's story** | **absent** | ✓ | 73% (behind the viewer) |
| Community feed | absent | absent | 73% |

Friends' stories use a different container, so the recommendations anchor cannot reach
them. But the second column is the interesting one. When a story opens full-screen the
feed underneath **stays in the node tree and keeps reporting on-screen bounds** — so the
Discover anchor matched at 73% while a friend's story was open, and only the friends'-row
veto was preventing a block. Open a friend's story after scrolling past that row and it
would have been blocked.

`base_image_layer_container` exists only while a viewer is open, so it now vetoes the feed
anchor too. Two independent reasons a friend's story cannot be blocked, rather than one
that happened to hold.

And it is a **switch**, not a decision. "Block the feed but not my friends" and "block all
of it" are both legitimate, and which is right is not Heed's to fix permanently — so
anchors can name a carve-out, and the app offers it per rule. Stored as the *disabled*
set, so a carve-out added later is on by default: a new exception can only ever protect
something that was previously blocked, which is the safe direction to be wrong in.

Two properties keep this safe. Heed only ever blocks on a **positive** match, so anything
it cannot name is allowed — a redesign that breaks every id above fails open, into doing
nothing. And a taught "allow" surface beats a shipped anchor, so a false positive is
fixable by the person hitting it.

Also note what has no anchor at all: LinkedIn. Nothing in it names the feed the way
Spotlight names itself, and Mindful does not detect it either. LinkedIn stays on the
behavioural path — a scrolling budget and a nudge — which is what that path is for.

### The mechanics

`flagReportViewIds` is the single flag this feature turned on. Without it Android returns
null for `viewIdResourceName` on every node, so a fingerprint built from view ids is a set
of nulls and no anchor can ever match. It was missing from
`accessibility_service_config.xml`, which meant precise detection had never worked at all
— not misconfigured, not unlucky, simply incapable of firing. Anchors are now looked up
with `findAccessibilityNodeInfosByViewId`, an indexed query, rather than by walking the
tree; the walk remains only for screens you teach it.

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
| Break the feed | A pause every N scrolls. Takes nothing away, and repeats. |

**The seam.** An infinite feed works by never presenting a last post, so carrying on is
never a decision — there is no moment at which you choose to keep going, only a moment at
which you have not stopped. Break the feed manufactures that moment: after N scrolls the
feed is covered, a timer runs, and one deliberate tap hands it straight back. Then it
counts again.

It is the only control here that takes nothing away, which is what lets it repeat. A limit
fires once and is spent; a nudge argues once per visit and gives up. This costs twenty
seconds and returns you exactly where you were, so meeting it four times in a sitting is
not a fight.

In Precise mode the count only accrues on a screen recognised as a feed, so conversations
cannot trigger it. In Automatic there is no such thing as a feed — only scroll events — so
it fires anywhere in the app, and the settings screen says so in red rather than implying
a precision that does not exist. A `Block` rule still wins: waiting out a pause in order to
be removed from the screen anyway would be friction charged for nothing.

**This paragraph used to say the opposite,** and it is worth leaving a marker where it
stood. It claimed that blocking one *surface* — Snapchat's Spotlight but not its chats —
required reading the screen and that Heed deliberately could not, so a tight scroll budget
was the approximation. That stopped being true when precise matching was added, and the
approximation it described is the thing that threw people out of conversations. Precise
mode does the surgical version, on named anchors, and a scroll count may now only ever
block in Automatic mode. Two sections of this file described a privacy boundary the code
had already moved; the code was audited and they were not.

**The limitation that is real** is which apps can be named. Precise mode works where Heed
or the user can identify the feed, and LinkedIn — see above — has nothing that names it.
Those apps stay on the behavioural path, where a scroll budget and a nudge are genuinely
all that is available.

### Detecting the behaviour, not the screen

Doom scrolling is not a place you go, it is a thing you do: long, fast, unbroken scrolling
you did not set out to do. So Heed measures that, rather than trying to recognise
particular feeds.

The accessibility service **does** hold `canRetrieveWindowContent`, and this file claimed
for several releases that it did not. It is the flag precise matching needs, and a false
reassurance about a privacy boundary is worse than no claim at all — so what is true is
stated instead, and it is narrower than "reads your screen":

- Only `viewIdResourceName` and `className` are ever read from a node — the structural
  skeleton of a layout. Grep `focus/` for `.text` and `contentDescription`: there are no
  reads. It recognises the shape of a feed without learning a word on it.
- The tree is only walked for apps explicitly set to Precise. Everything else uses the
  behavioural path, which needs no content access whatsoever.
- `AccessibilityServiceInfo.packageNames` names only apps with a rule, known scrollers and
  known scrollers, so for every other app on the phone the tree is never even offered.

Behavioural measurement remains the default and the fallback, because it needs none of
that and works in an app nobody has heard of yet.

A session counts as scrolling when it clears **both** a sustained rate (25 scroll events
per minute) and one unbroken stretch (60s). Either alone is a false positive: a high rate
on its own is hunting through a list, and a long burst on its own is one flick down a long
article. Pausing to read breaks the stretch, so this measures the trance, not the time.

The intervention is friction, not a wall — a five-second delay and one honest sentence
about how you got there, drawn with `TYPE_ACCESSIBILITY_OVERLAY` so it needs no
`SYSTEM_ALERT_WINDOW` permission. Apps that hard-block get uninstalled by Friday.

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

## Focus sessions

Every limit elsewhere in Heed is per-app and reactive: you have had enough of this one.
A focus session asks the opposite question — everything is shut unless you named it — and
that inversion is why it gets its own screen rather than another slider on the app list.
Pick a kind of session, pick a length or a stopwatch, and the phone closes.

The load-bearing part is not the timer. **Starting is one tap and stopping takes ninety
seconds.** The person who set the session and the person who wants out of it four minutes
into a boring paragraph are not the same person, and only one of them was thinking about
the afternoon. Everything else on that screen is bookkeeping around that asymmetry.

Ninety seconds rather than never, and the difference matters. A session you genuinely
cannot leave is a promise Android will not keep — force-stopping Heed ends it instantly,
and so does turning the accessibility service off — so an app that claimed to be
uninterruptible would only be lying to the people who believed it. The delay is honest
friction, not enforcement, and the screen says so.

Three exemptions, none of them preferences. `CriticalApps` already refuses to block
authenticators, diallers, alarms and password managers, and a session is not a reason to
change that. The **launcher and Heed itself** are exempt because blocking either is a trap
rather than a rule: blocking bounces you to the home screen, so a blocked home screen has
nowhere to send you, and a blocked Heed hides the only button that ends the session.

One thing a session does that nothing else in Heed does: while it runs, the accessibility
service's `packageNames` filter is dropped entirely. That filter is the single biggest
thing Heed does for its battery, and a session is the one case that genuinely needs the
wide net — it turns apps away *precisely because* you never made a rule for them, and an
app the system filters out is one the service never hears about. Bounded by the session,
and back to the narrow set the moment it ends.

## Whose time is it

Two per-app settings that change what the statistics mean rather than what Heed does.

**Leave out of the statistics** drops an app from every total, chart and list — done in
SQL rather than filtered afterwards, so the headline figure and the app list can never
disagree about it. For the apps that are foreground time without being *your* time: a
launcher you pass through on the way to something else, the intent resolver, a system
dialog. Counting twenty seconds of launcher between two real sessions does not make the
total more accurate, it makes it less.

**Productive or distracting** colours the charts. Deliberately your judgement and never
Heed's: the same app is a lecture hall for one person and a slot machine for the next, and
a category shipped in a list would be wrong about the app that matters most. Unsorted is
the default and stays uncoloured, because a screen that shades every row says nothing —
the eye needs somewhere neutral to rest before a red bar means anything. Each day's bar
is then stacked by category, with the split named in words underneath, since a colour
alone tells you a distinction exists and not which way round it goes.

## Saying less, without saying less

Heed explains itself more than most apps do, and that is deliberate: a filter you cannot
interrogate is one you stop trusting the first time it is wrong. But *available* and
*unavoidable* are different things, and every settings card had been written as three
paragraphs at full volume.

On screen that produces the opposite of the intent. When everything is explained equally
loudly nothing is emphasised, the control you came for is below the fold, and the reader
learns to skip the prose wholesale — including the two sentences that mattered. The
per-app screen was four scrolls of reasoning wrapped around six controls.

So the reasoning is still there, one tap away, and the visible line is now the state:
"Drain the colour here / Off / Why ⌄". Nothing was deleted; the same words are behind the
chevron. The screen went from four scrolls to one and a half.

Two rules about where this is not used. **Warnings are never collapsed** — an error whose
explanation is hidden behind a tap is one you have to opt into understanding, which is how
a warning becomes decoration. And the **first** description of a control that can surprise
you stays visible: what Automatic mode cannot do is on screen, because someone who does
not read it will be thrown out of a conversation.

Material You is already in use — the palette is `dynamicDarkColorScheme` from your
wallpaper on Android 12 and up, with a fixed scheme as the fallback. The two category
colours are deliberately *not* dynamic: productive and distracting have to mean the same
thing on every device, and a wallpaper-derived accent cannot promise that.

## Reading the numbers

Two small things that decide whether the Attention screen answers a question or just
displays one.

The bar charts are scaled to their own peak, which shows the *shape* of a week — the only
thing that tells you whether Tuesday's rule changed anything — but makes every week look
the same height, so the chart cannot say whether four hours is a lot. Hour gridlines put
the absolute answer back without giving up the scaling, and tapping a bar names the exact
figure: "Tuesday: 2h 14m". On the whole-phone chart, tapping a day also re-queries the app
list underneath it, so the chart and the list never show different things. The lines step
to two- and four-hour intervals on a heavy week rather than turning into ruled paper, and
they are drawn only on the time series — ruling a count of app opens with hour lines would
be a chart that lies quietly.

The inbox's three tabs — Needed, Filtered, Everything — are a pager, so they swipe. The
tab row and the pager each follow the other, because driving them independently is how you
end up reading "Needed" over a list of filtered notifications.

Making them a pager introduced one bug worth recording, because it is invisible in code
review and obvious on a phone. `HorizontalPager` centres its pages vertically by default,
and a `LazyColumn` shorter than the viewport wraps its content — so a tab holding four
notifications drew them as a block floating in the middle of an empty screen. Neither
component was wrong on its own; the default only becomes visible when something is placed
inside something else.

Bedtime and screen access used to live on this screen and now live in Settings. They are
each read once and changed almost never, and they were occupying the top third of the one
screen you open to answer a question.

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
focus/      scroll watcher, surface matching, focus sessions, the friction overlay
notify/     re-raising alerts, inline feedback action
ui/         Compose: onboarding, inbox, detail ("why"), settings, per-app rules
```

## What the audit removed

A read of every file, looking for things that had survived a rewrite without surviving
its reasoning. The rule applied was that each line should be defensible on its own terms.

**Two copies of the scrolling decision, and only the untested one ran.** `FocusEnforcer`
still held an `onScroll` that read the rule and the daily total from Room per event. When
that turned out to cost most of the battery it was reimplemented inline in the service
against a cached rule — and the original was left behind, with four test classes still
exercising it. They passed for weeks against code that never executed, which is a worse
position than having no tests, because they reported the behaviour was covered. The
decision is now [ScrollDecision], a pure function the service calls and the tests call.

**A comment that claimed the opposite of the truth.** `ScrollWatcherService` documented
itself as running *without* `canRetrieveWindowContent` and therefore incapable of being
given the screen's text. That stopped being true when precise matching was added. A false
reassurance about a privacy boundary is worse than none, so the class now states plainly
what it holds, what it reads, and why the difference is the point.

**A field that always said zero.** `AttentionStat.scrollingSessions` was computed by an
in-memory builder that SQLite replaced; the production path passed a hard-coded `0` while
the builder stayed alive purely to satisfy its own tests.

**An enum value no version could ever produce.** `CapturePath.ASSISTANT`, for the
`NotificationAssistantService` path that turned out to be `@SystemApi` and unavailable to
any third-party app — along with `scoreFast`, the non-suspending scorer that existed only
to meet that API's timing budget, and a class comment describing two scoring paths where
one had had a caller for months.

Plus: five private copies of "midnight this morning" and two of the duration formatter
(now `core/Time`, because a screen-time total and the limit enforced against it must agree
about when the day starts); `SurfaceCapture.hasAnchor`, superseded by the bounds-aware
version; a `connected` flag written and never read; dead DAO queries; and a private
`contentColorFor` shadowing Material3's function of the same name.

### The bug it turned up

Worth recording separately, because the audit found it and no test would have.

`warmCaches` had been given a run-once guard *and* took its `CoroutineScope` from whoever
called first. Those are fine apart and broken together: the first caller was often
`AttentionService`, which is stopped whenever no rule needs it, and cancelling its scope
killed every cache collector while the guard refused to let anything restart them. The
rule cache then stayed empty for the life of the process, `cachedRuleFor` returned null
for every app, and **blocking silently stopped working entirely** — with the rules still
showing as set in the UI, which is the worst way for it to fail.

It reproduced on the release build and not on debug, purely because service startup order
differed. The repository is a process-lifetime singleton, so it now owns a scope with the
same lifetime and `warmCaches()` takes no argument — the borrowing is not a mistake that
can be made again.

## Signing and the debug build

Every build that reached the phone until now was a *debug* build, carrying the
`DEBUGGABLE` flag — which lets anyone with adb read the app's database through `run-as`.
For an app whose whole premise is that your notification history stays on the device,
that was the wrong thing to leave in place. Release builds are now signed from a keystore
kept out of the repository (`keystore.properties`, gitignored), so they install as
ordinary updates and carry no debug flag.

Losing that keystore means no future release can update an existing install in place, so
it is worth backing up.

## Build

Needs JDK 17–21 (not 26 — AGP rejects it) and the Android SDK.

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # 138 tests over features, rules, classifier, pipeline, scrolling, sessions
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
