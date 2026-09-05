# Attention, limits and focus

The second half of Heed: where your time goes, and the rules you set about it. For the
notification filter, see [How the filtering works](how-it-works.md). For how Heed tells
one screen from another, see [Feed detection](feed-detection.md).

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

## Rules: budget the scrolling, not the app

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
or the user can identify the feed, and LinkedIn — see [Feed detection](feed-detection.md)
— has nothing that names it.
Those apps stay on the behavioural path, where a scroll budget and a nudge are genuinely
all that is available.

## Groups: one budget across several apps

Per-app limits have a hole in them that anyone who has used one has found. Thirty minutes
of Instagram, thirty of TikTok and thirty of Snapchat is an hour and a half of the same
activity, and every one of those limits reports success. The apps are interchangeable —
that is the whole point of them — so a limit that treats them separately is a limit you
satisfy by switching apps, which costs one tap and feels like obeying the rule.

A group is the honest version. Name the habit rather than the app, put the interchangeable
apps in it, and give the group one budget: minutes a day, opens a day, or minutes of
scrolling a day. The count is shared, so switching between members spends the same budget
and resets nothing.

Attention → **Groups**. Three properties are worth knowing:

- **A group limit applies to a member with no rule of its own.** That is the point of it:
  most members will never be worth a per-app rule, and a budget that only bound the apps
  you had already bothered to configure would bind the ones that needed it least.
- **An app belongs to at most one group.** Two groups claiming the same app would make
  "how much is left" a question with two answers, and there is no honest way to pick
  between them — so adding an app to a group takes it out of whichever group had it.
- **Critical apps are still never blocked.** An authenticator dropped into a group by
  accident is still an authenticator; see below.

The numbers on the group screen are read with the same queries the enforcement uses,
rather than derived from the statistics — which leave out any app you marked *not counted*.
A bar that showed room left while the limit was firing would be worse than no bar.

## Apps Heed will never block

`CriticalApps` refuses to block authenticators, diallers, alarms, password managers and
settings, whatever rule is set on them and regardless of bedtime. A focus app standing
between you and a one-time code at 3am has stopped being useful and become a hazard, and
the rule that does it is almost never one you meant to set — this exists because a stray
Block rule landed on an authenticator during testing. Matching is by package and by
keyword, because the long tail of authenticator and banking apps cannot be enumerated and
erring the other way is much worse.

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
