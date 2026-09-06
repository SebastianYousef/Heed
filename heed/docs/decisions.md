# Design decisions, and the ones that were reversed

A log of the choices that are not obvious from the code, including the several that were
wrong the first time. Kept because the reasoning is the part that is expensive to
reconstruct, and because a removed feature leaves no trace anywhere else.

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

## Long press, not swipe, to delete

Swipe-to-delete is the obvious gesture and the wrong one here. The inbox tabs are a pager,
so a horizontal drag on a card is already how you move between Needed, Filtered and
Everything; a `SwipeToDismissBox` inside that pager wins the gesture from it, and tab
swiping — asked for deliberately — would break without a word being said about it. Swiping
also deletes one row per gesture and raises one snackbar per row, where what was being
asked for was clearing out several at once. Long press starts a selection, tap adds to it,
and one bin deletes the lot with one undo.

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
