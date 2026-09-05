# Feed detection

How Heed tells Snapchat's Spotlight from Snapchat's chats — the one thing behaviour alone
cannot do, and the reason the accessibility permission exists at all. What that permission
can and cannot see is in [Privacy](privacy.md).

## Detecting the behaviour, not the screen

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

## Telling one screen from another

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

## Friends' stories: checked, not assumed

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

## The mechanics

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
project. No code was taken from it — only the observation that a given view id names a
given screen, which `uiautomator dump` prints for anyone who looks.
