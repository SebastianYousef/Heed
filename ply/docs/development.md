# Building and hacking

## Layout

```
build-logic/    the convention plugins: shared Android config, signing, the no-network guard
keel/           the shared base — theme, disclosure, chart, stepper, day arithmetic
heed/           the other app in this repository, not yet built on the base
ply/            this app, as the :ply module
  data/         Room entities, DAO, repository, settings, exercise seeding
  train/        weights, 1RM, records, volume, plates, the rest timer service
  move/         step sensor, reconciler, worker, boot receiver
  ui/           Compose: session logging, picker, history, movement, settings
  widget/       Glance home-screen widget
```

`keel` was a module rather than a copied folder so that the day a repository grew around it,
nothing but `settings.gradle.kts` would change. That day has come: this is the Keel
repository, and Ply is one of two apps in it.

## Build

Needs **JDK 21** and the Android SDK with platform 37 installed. AGP rejects JDK 26, which
is likely to be what `java` on your path is.

```bash
export JAVA_HOME=/home/linuxbrew/.linuxbrew/opt/openjdk@21
./gradlew assembleRelease
./gradlew testDebugUnitTest       # 54 tests: units, 1RM, records, volume, plates,
                                  # steps, and the plate-inventory parser
```

Install to the owner profile only:

```bash
adb install -r --user 0 ply/build/outputs/apk/release/ply-release.apk
```

Without `--user 0`, `adb install` puts a copy in *every* profile including a private space.
That is not cosmetic — it is the whole cause of a long-running bug in the previous project,
where the copy living beside four banking apps was the one they were reacting to.

## Toolchain versions are not free to change

AGP 9.4.0, Gradle 9.7.1, Kotlin 2.3.21, KSP 2.3.11, and no Kotlin plugin applied. That
combination is the only one found that supports Room, Material 3 Expressive and `targetSdk`
36 at once; [decisions.md](decisions.md) records the four that do not and why. In
particular:

- Applying `org.jetbrains.kotlin.android` alongside AGP 9 **cannot work** — KGP configures a
  class AGP 9 deleted — even though AGP's own error message suggests it.
- KSP 2.2 refuses to run against AGP 9's built-in Kotlin. KSP 2.3.11 does not. That single
  version is the difference between this repository building and not.

## Signing

Release signing reads `keystore.properties` at the repository root, which is gitignored
along with the `.jks` itself. If it is absent, the release build simply goes unsigned rather
than failing, so a fresh clone still builds and still runs the tests.

**Back the keystore up.** Losing it means no future release can update an existing install
in place, and on Accrescent it means the listing can never be updated at all.

## Warnings are errors

`allWarningsAsErrors` is on for every module. A deprecated icon fails the build, which is
the point: warnings are the early form of the bugs an audit finds later, and a project that
tolerates a few accumulates a screenful nobody reads.

## Room

`exportSchema` is on and `ply/schemas/` is committed, so a migration can be reviewed against
the shape it actually produced rather than the shape its author believed it produced.
`fallbackToDestructiveMigration` is not used and will not be: a training history cannot be
regenerated, there is no copy anywhere, and a year of it represents a year of turning up.

`PlyDatabase.SCHEMA_VERSION` is a constant that the `@Database` annotation reads, so the two
cannot drift — a migration that forgets to raise it fails at build time rather than
mislabelling a bug report.

## Tests

Anything with a rule or a sum in it is a pure function taking its inputs explicitly, and is
tested. That shape is not stylistic: the previous project had two copies of one decision,
the tests exercised the copy the phone never ran, and they passed for weeks while the
behaviour they claimed to cover was broken.

```
LoadTest              grams, stepping onto the grid, formatting, float drift
OneRepMaxTest         Epley, the single case, the refusal above twelve
RecordsTest           the three kinds and where they disagree
VolumeTest            hard sets, tonnage, warm-ups, the double-listed muscle
PlatesTest            greedy loading, awkward inventories, shortfalls
StepReconcilerTest    reboots, hub resets, batches, first reads, bad values
```
