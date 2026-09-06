# Design decisions, and the ones that were reversed

The choices that are not obvious from the code, including the ones that were wrong the
first time. Kept because the reasoning is the expensive part to reconstruct, and because a
removed feature leaves no trace anywhere else.

## The toolchain took four attempts, and none of the failures were obvious

Recorded in full because anyone touching the build files will otherwise repeat it.

The target was: Room with hand-written migrations, Material 3 Expressive, `targetSdk` 36 —
which Google Play has required for new submissions since 31 August 2026 and which Accrescent
enforces by *removing* non-compliant apps rather than merely hiding them.

**Attempt 1 — AGP 8.13.2 with Gradle 9.7.1.** Failed at configuration: AGP 8.13 relies on
`org.gradle.api.problems.internal.InternalProblems`, removed in Gradle 9.6.

**Attempt 2 — AGP 9.4.0.** Configured, then refused to run KSP: *"KSP is not compatible with
Android Gradle Plugin's built-in Kotlin."* AGP 9 compiles Kotlin itself. Room's compiler is
KSP, and hand-writing what Room generates is not a trade worth making.

**Attempt 3 — AGP 9.4.0 with `android.builtInKotlin=false` and the Kotlin plugin applied,**
which is what the error message instructs. Failed applying `org.jetbrains.kotlin.android`:
`ApplicationExtensionImpl cannot be cast to BaseExtension` — KGP 2.2.21 configures a class
AGP 9 deleted. Kotlin 2.3.21 fails identically. **The Kotlin Android plugin cannot be
applied alongside AGP 9 at all**, so the error message's own advice is a dead end.

**Attempt 4 — back to AGP 8.13.2 with Gradle 9.5.1.** Everything configured and then
`checkDebugAarMetadata` produced eighteen findings: every current AndroidX release —
`core-ktx` 1.19, Compose 1.12, `navigation-compose` 2.10, and `material3` 1.5.0-alpha —
requires AGP 9.1 or higher. AGP 8.13 is the last of the 8.x line, so that branch buys a
Compose generation a year old and no Expressive at all.

**What works: AGP 9.4.0, Gradle 9.7.1, Kotlin 2.3.21, KSP 2.3.11, built-in Kotlin left
on.** KSP 2.2 refuses to run against built-in Kotlin; **KSP 2.3.11 does not.** That single
version is the whole difference, and it is why the second commit's `android.builtInKotlin=false`
is gone again.

Two consequences worth knowing:

- **No Kotlin plugin is applied.** Compiler options are set on the compile tasks rather than
  through a `kotlin { }` block, so they hold whether Kotlin is compiled by AGP or by KGP —
  the extension exists in one of those worlds and not the other.
- **`compileSdk` is 37 while `targetSdk` is 36.** The libraries require compiling against 37;
  36 is what the stores require targeting. `targetSdk` will move to 37 deliberately, after
  the Android 17 behaviour changes have been read, rather than as a side effect of a
  dependency bump.

### Material 3 Expressive costs an alpha, and it is worth it

Expressive is what Material You looks like in 2026 — the motion scheme, the shape morphing,
the wider type scale. In stable `material3` 1.4.0 every one of those symbols is `internal`:
not experimental, not opt-in, **unreachable**. It exists only in the 1.5.0 alphas.

So `material3` is pinned to `1.5.0-alpha27` explicitly rather than taken from the Compose
BOM. Pinned exactly, so an alpha cannot move underneath a release. The reversal is small and
is the reason this is an acceptable risk: drop `MaterialExpressiveTheme` for `MaterialTheme`,
delete four opt-ins, and the app is on stable Material You with a duller motion curve.

## `minSdk` is 33, and that is a deletion rather than a restriction

Every SDK level below the minimum costs a branch that will never execute on a device anybody
using this owns. Dynamic colour is unconditional from 31; `POST_NOTIFICATIONS` is a runtime
permission from 33. Below that, `KeelTheme` would need a hand-maintained fallback palette
and the notification code would need a version check, and both are the kind of never-run
code an audit deletes two years later wondering whether it ever did anything.

The cost is real and is accepted: Android 12 and older cannot install Ply.

## Weights are whole numbers of grams

The obvious storage is kilograms as a float, and it is wrong. A record is decided by
comparing two weights for order or equality, and 62.5 is not representable in binary
floating point — so two sets logged as the same weight by two different paths can compare
unequal, and a tonnage summed over forty of them drifts.

Grams rather than any coarser fixed-point unit because the value has to survive being
displayed in pounds, and 1 lb is 453.59237 g exactly — whole in grams and fractional in
anything larger. An integer costs SQLite exactly what a real does.

## A set stores three weights and one estimate, and none of them can go stale

`weightGrams` is what was *added* — plates, dumbbell, the belt on a dip, negative for
assistance. `bodyweightGrams` is what the lifter weighed, snapshotted, present only for
exercises where that is part of the load; snapshotted rather than looked up, because a
pull-up done at 78 kg does not become a heavier set when you gain three.
`effectiveGrams` is the sum and is what every comparison uses.

`e1rmGrams` is stored for a sharper reason than convenience. "Best estimate before this
moment" wants to be a `MAX()` over an index. Computing it in SQL would mean writing Epley
*and* its refusal above twelve reps into SQL as well as into Kotlin — two implementations of
one rule. The previous project shipped exactly that arrangement for the scrolling decision,
and four test classes exercised the copy the phone never ran, passing for weeks against dead
code. That is a worse position than having no tests, because it reports the behaviour as
covered.

Both derived columns are pure functions of their own row, so neither can disagree with it,
and `WorkSet.of` is the only way to construct one.

## Three kinds of record, never merged

"Personal record" sounds like one thing and is at least three, and they conflict constantly.
A heavy single beats every set of ten ever done on *heaviest* and loses on *best estimate*.
Adding a rep at the same weight beats nothing on the first and both of the others.

Each is named on screen and none is called *the* record. An app that lights up a trophy
without saying which of these it meant teaches you to ignore the trophy.

Records are judged against what was true **strictly before** the set. Read the bests after
the insert and the set beats itself, and everything is a personal record forever.

## Volume is counted twice because there is no single right way

Hard sets is the count the training literature is written in and is blind to load. Tonnage
is sensitive to load and is dominated by whatever moves the most mass — a squat outweighs a
curl tenfold regardless of how hard either was. Both are shown and the app says which is
which, because printing one number called "volume" is picking one of them on the reader's
behalf without telling them.

Warm-ups are excluded. Secondary muscles count at half — a convention rather than a
measurement, which is exactly why it is stated on screen rather than presented as a fact.

## The exercise library is in SQLite, not parsed from the asset

The tempting alternative is to keep 876 exercises in memory and only store what the user
adds. It fails on the aggregation: volume per muscle per week is a `GROUP BY` over sets
joined to their exercises, and with the library outside the database that join has to happen
in Kotlin — loading every set into the heap to answer what SQLite answers without
materialising a row. The previous project's statistics screen did that with four thousand
rows on every database change, and fixing it was most of a performance release.

Seeding runs on first launch rather than shipping a prebuilt `.db`, because a prebuilt
database makes the schema a binary artifact that has to be rebuilt and re-verified on every
migration.

The 96 MB of exercise images are **not** vendored; they were stripped from the file. The
instructions are, at 800 KB, which is what lets the app explain a movement with no network
permission — the thing that would otherwise force one.

## No photos, and that is a feature

Progress photos are the most sensitive thing an app like this could hold and they contribute
nothing to any number in it. An app with no network permission still sits in a filesystem
that other things can be granted access to, and "we never upload them" is a weaker promise
than not having them.

## The rest timer uses two clocks

The countdown on screen is Android's own chronometer, drawn by the system from a target
time: no per-second update, no wakeup, no work at all while it runs down.

The alert at zero is a separate exact alarm. A foreground service keeps the *process* alive
but does not keep the CPU awake, so a `delay()` counting through a doze can fire late — and
a rest timer that is forty seconds late is worse than no rest timer, because you trusted it.

`USE_EXACT_ALARM` rather than `SCHEDULE_EXACT_ALARM`: it is granted at install with no
prompt, and it is restricted to apps whose user-facing purpose is alarms and timers, which
is exactly and only what this uses it for.

## The guard belongs to the base, not to the app

The previous project's privacy claim rested on a Gradle task that failed the build if
`INTERNET` appeared in the merged manifest. A second app means a second copy of that task,
and a guard that holds in one of two apps is worse than no guard because it still reads as a
policy.

It is a convention plugin now. It also reads the merged manifest through `SingleArtifact`
rather than by matching task names against a regex, so a rename inside AGP cannot quietly
leave it matching nothing, and it declares its inputs and outputs so it survives the
configuration cache instead of forcing a rebuild.

It is attached to `assemble`, not only to `check`. The thing that must never happen is an
APK existing — not a test being skipped.

### One reversal already

The check was first wired with `tasks.named("assemble$name")` inside the variant callback.
Under AGP 9 that callback runs *before* the assemble task is registered, so it failed the
whole build at configuration time with `Task with name 'assembleDebug' not found`. It is
matched lazily now.

## Version numbers are not being spent early

Ply starts at 0.1.0 and will stay in the 0.x line until it has been used for months without
wanting changes. The previous project reached 1.0 in four days, which said nothing true
about it — a version number is a claim about how settled something is, and spending it early
leaves nothing to say when it becomes true.

For Accrescent this also matters mechanically: `versionCode` may never decrease, so a
version number spent is spent.
