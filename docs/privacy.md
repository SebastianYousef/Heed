# Privacy

Heed reads every notification you receive, watches which apps you use and — if you turn it
on — observes scrolling. That is a great deal of trust to ask for. This page is what makes
it reasonable to give.

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

## What the screen access can and cannot see

The accessibility service **does** hold `canRetrieveWindowContent`. An earlier version of
this file claimed it did not, and a false reassurance about a privacy boundary is worse
than no claim at all. What is true is narrower than "reads your screen":

- Only `viewIdResourceName` and `className` are ever read from a node — the structural
  skeleton of a layout. Grep `focus/` for `.text` and `contentDescription`: there are no
  reads. It recognises the shape of a feed without learning a word that is on it.
- The tree is only walked for apps explicitly set to Precise. Everything else uses the
  behavioural path, which needs no content access at all.
- `AccessibilityServiceInfo.packageNames` names only apps with a rule, apps in a group and
  known scrollers, so for every other app on the phone the tree is never even offered.
  (A running focus session is the one exception, and it is bounded by the session — see
  [Attention](attention.md).)
- Everything except scrolling — time limits, opens, bedtime, grayscale — runs without it,
  so the app degrades to something useful rather than to nothing when it is off.

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
