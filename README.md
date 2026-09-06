# Keel

Two Android apps by one author, and the base they are built on.

| | | |
|---|---|---|
| **[Ply](ply/)** | A training log and step counter, arranged around costing one tap to record a set. | `0.1.0` |
| **[Heed](heed/)** | A notification filter and attention tracker, that says what an interruption cost you. | `0.5.1` |
| **`keel/`** | The base: theme, components, one chart at every scope, day arithmetic, and the build conventions. | — |

Each app has its own README — **[Ply](ply/README.md)**, **[Heed](heed/README.md)** — with
what it does, how to install it, and what it will and will not do to your data. This page
is about the family and the part they share.

Neither app has the `INTERNET` permission. Not as a promise about the code: the build fails
if that permission reaches a merged manifest, and without it the kernel will not open a
socket for the process at all. There is no account, no cloud, no analytics and no crash
reporting in either app.

---

## The base

`keel` is a shared Android library module, plus the Gradle convention plugins in
`build-logic/` that configure both apps and carry the no-network guard.

It exists because of a specific failure. Heed grew two charts that diverged for no reason
anybody chose — one on the day screen, one on the app screen — and by the time that was
noticed they disagreed about what a bar meant. A component built once and used at every
scope cannot do that. The same argument applies to the network guard: a guard that holds in
one of two apps is worse than no guard, because it still reads as a policy.

Keel is a module in this repository rather than a repository of its own, and is not
published as an artifact anywhere. That is deliberate. A published base would mean the two
apps could sit on different versions of it, which is the drift it was built to prevent —
only now with a version number attached, so it would look intentional. Here, a change to
the base and to both apps that consume it is one commit that either compiles or does not.

Its scope is meant to grow. Room and migration conventions, settings storage, export and
redaction, widget and background-work scaffolding are all still duplicated between the two
apps, and belong here.

## Building

**JDK 21** — AGP rejects 26 — and the Android SDK with platform 37.

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew assembleRelease          # :keel and :ply
```

**Heed builds separately, from `heed/`.** It is on AGP 8.7.3 and Gradle 8.9 while the rest
of the tree is on AGP 9.4.0 and Gradle 9.7.1; those cannot share one Gradle invocation, so
there are two builds here until Heed is ported onto the base. See
[Heed's status page](heed/docs/status.md).

Release builds are signed from a keystore kept out of the repository. A clone without one
still builds and still runs the tests.

## Reading further

The reasoning lives with each app: [Ply's docs](ply/docs/README.md),
[Heed's docs](heed/docs/README.md). Both keep a `decisions.md` that records the choices
that were wrong the first time, and a `status.md` that separates what has been verified on
a phone from what has not.

## Licence

GPL-3.0-or-later, covering everything in this repository — both apps and the shared base.
You are free to use, study, change and share it; if you distribute it or anything built on
it, that has to come with source under the same licence, and the copyright notices stay on.
Closed-source redistribution is not permitted.

Copyright © 2026 Sebastian Yousef. See [LICENSE](LICENSE).

## Author

Written and maintained by **Sebastian Yousef**
([@SebastianYousef](https://github.com/SebastianYousef)), the sole contributor.

---

This repository was `SebastianYousef/Heed` until Ply joined it. That URL redirects here, and
no published commit was rewritten to make the move.
