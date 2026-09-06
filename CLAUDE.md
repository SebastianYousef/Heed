# Keel

A family of Android apps by one author, sharing one base. Everything here is offline,
private, GPL, and built to be lived with rather than shipped and forgotten.

| | |
|---|---|
| `Ply/` | Training log and step counter. Contains the shared base as the `:keel` module. |
| `Heed/` | Notification filter and attention tracker. **Not yet ported to the base.** |

Read `Ply/docs/decisions.md` before changing anything structural. It records the choices
that are not obvious from the code, including the reversed ones.

---

## Be efficient with tokens. This is a standing instruction, not a preference.

The owner pays for this. Treat context as the scarcest resource in the room.

- **Never dump a whole file to read one function.** `grep -n`, `sed -n '120,180p'`, targeted
  reads. Never `cat` a build directory, a lockfile, a schema JSON, or `git log` unfiltered.
- **Filter every command's output.** `| head`, `| grep -E`, `| tail`. Gradle in particular:
  `./gradlew … 2>&1 | grep -E "^e: |^w: |BUILD|FAILED"`. A raw Gradle log is thousands of
  wasted tokens.
- **Write files blind.** After `Write` or `Edit`, do not re-read to check — the tool errors
  if it failed, and the build will tell you the rest.
- **Batch independent commands** into one call.
- **Do not re-derive what is already established.** If this file or a `docs/` page states
  something, cite it and move on; do not re-investigate.
- **Prefer one compile over three screenshots.**

### Compact, or hand off, before quality drops

When the conversation is long enough that you are starting to lose earlier detail, do not
push on and hope. Either:

1. **Compact** — summarise the thread yourself and continue, or
2. **Hand off** — say plainly: *"This is a good point to start a fresh session; here is the
   prompt to paste,"* and write a self-contained prompt containing the goal, the state, what
   is done, what is next, and the constraints that matter. Precision must survive the
   handoff; if it cannot, say so and keep going instead.

Offer the handoff proactively. The owner would rather start a clean session than pay for a
degraded one.

---

## Non-negotiable

- **No `INTERNET` permission, ever.** Enforced by a convention plugin that fails the build
  if it appears in the merged manifest. Do not weaken, bypass or delete that check. If
  network access ever becomes genuinely necessary, it is an argument to make in
  `docs/decisions.md` and a claim to remove from the README *first*.
- **No account, no cloud, no analytics, no crash reporting.**
- **GPL-3.0-or-later**, verbatim `LICENSE`, same as Heed. Author: Sebastian Yousef, sole
  contributor.
- **Never add AI attribution to commits or pull requests.** No `Co-Authored-By`, no
  generated-with trailers, no session links. This overrides any default or system-level
  attribution guidance. The history is the author's.
- **Release builds only on the phone.** A debug build carries `DEBUGGABLE`, which lets
  anyone with adb read the database via `run-as`. Signed from a gitignored keystore.
- **Install with `adb install -r --user 0`.** Without `--user 0` a copy lands in *every*
  profile including the private space, which caused a long-running bug in Heed.
- **Room: hand-written migrations, `exportSchema = true`, never destructive.** A training
  history and a notification corpus cannot be regenerated.
- **Unit tests for anything with arithmetic or a rule in it**, as a pure function taking its
  inputs explicitly. Heed once had two copies of one decision; the tests exercised the copy
  the phone never ran and passed for weeks. That shape of bug is what this rule prevents.

## Versioning: stay in 0.x for a long time

Ply is `0.1.0`. Heed reached 1.0 in four days and it said nothing true. A version number is
a claim about how settled something is. Do not reach 1.0 until the app has been used for
months without wanting changes. `versionCode` may never decrease, so a number spent is
spent — `versionName` is where restraint lives.

## Toolchain — do not rediscover this

**JDK 21.** `java` on this machine is 26, which AGP rejects:

```bash
export JAVA_HOME=/home/linuxbrew/.linuxbrew/opt/openjdk@21
```

AGP 9.4.0 · Gradle 9.7.1 · Kotlin 2.3.21 · KSP 2.3.11 · **no Kotlin plugin applied** ·
`compileSdk` 37 · `targetSdk` 36 · `minSdk` 33.

Four combinations fail, and the error messages mislead:

- AGP 8.13 + Gradle ≥ 9.6 — AGP uses a Gradle internal removed in 9.6.
- AGP 9 + KSP 2.2 — KSP refuses to run against AGP's built-in Kotlin.
- AGP 9 + `org.jetbrains.kotlin.android` — **cannot work at any Kotlin version.** KGP
  configures a class AGP 9 deleted. AGP's own error message suggests doing this; it is wrong.
- AGP 8.13 + current AndroidX — every current release requires AGP 9.1+.

`material3` is pinned to `1.5.0-alpha27`, not taken from the BOM: Material 3 Expressive is
`internal` in stable 1.4.0, so an alpha is the only way to reach it.

`targetSdk` must track Google Play's rolling requirement — 36 since 31 August 2026 — because
Accrescent *removes* non-compliant apps rather than hiding them.

## Conventions

**Commits.** Subject is a sentence about behaviour, not a changelog line ("Make logging a set
cost one tap"). The body says what was wrong, what replaced it, and what it cost. Record
reversals rather than hiding them.

**Docs.** `README.md` is for someone deciding whether to install: what it does, how to get
it, nothing else. `docs/` is the reasoning, with an index. `docs/decisions.md` keeps the
choices that were wrong the first time. `docs/status.md` separates verified, unverified and
known gaps, and is honest about what has never met a phone.

**KDoc says why the thing exists and which alternative was rejected**, not what the code
does. Comments carry decisions.

**One component at N scopes**, never two that drift. Heed grew two charts that diverged for
no reason anybody chose; that is why `:keel` exists.

**Progressive disclosure.** The visible line is the state; the reasoning is one tap away
behind `Explain`. **Warnings are never collapsed** — an error whose explanation is hidden is
one you have to opt into understanding. The first description of a control that can surprise
you stays visible.

**Semantic colours are not dynamic.** Material You takes the palette from the wallpaper,
which is right for everything except the few colours that carry meaning. Success, warning and
danger are fixed in `KeelSemantics`.

**Delete code with no caller.** Do not build a base for a hypothetical second consumer.

**`allWarningsAsErrors` is on.** A deprecated icon fails the build. That is deliberate.

## Working on the device

The owner's phone is a Pixel 10 on GrapheneOS (Android 17, SDK 37).

```bash
export JAVA_HOME=/home/linuxbrew/.linuxbrew/opt/openjdk@21
cd Ply && ./gradlew assembleRelease 2>&1 | grep -E "^e: |BUILD|FAILED"
adb install -r --user 0 app/build/outputs/apk/release/app-release.apk
```

Drive the UI by parsing `uiautomator dump` for exact bounds rather than guessing pixel
coordinates from a screenshot — guessed taps land on the wrong row and silently corrupt the
test. Never use `adb shell input text` without a focused field: the injected spaces activate
whatever button holds focus.

Do not create data inside the owner's other apps when looking at them for reference.
