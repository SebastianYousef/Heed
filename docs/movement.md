# Counting steps without spending battery

Android's `Sensor.TYPE_STEP_COUNTER` reports **steps since the device booted**. That is the
right design for a sensor and the wrong shape for a step count, and every bug a pedometer
has lives in the gap between them.

## What the counter does that a step count must not

- **It resets to zero on reboot.** A naive `now − lastSeen` goes sharply negative, and
  either subtracts a day's steps or, if clamped to zero, throws away everything walked
  since the restart.
- **It can reset without a reboot**, when the sensor hub restarts on its own.
- **It counted before the app existed.** A phone that has been up four days hands over
  30,000 steps the first time it is read.
- **Readings arrive late and out of order** when batching is used.

## The reconciliation

[`StepReconciler`](../app/src/main/java/io/github/sebastianyousef/ply/move/StepReconciler.kt)
is a pure function over explicit state, so every one of those cases is tested without
rebooting anything.

**A reboot is detected on the elapsed clock**, not on the counter falling and not on a
wall-clock boot estimate. `SystemClock.elapsedRealtimeNanos` is monotonic within a boot and
starts again at zero after one, so it is exact: there is no tolerance to tune, and an NTP
correction cannot look like a restart the way it can when boot time is computed as
`now − elapsedRealtime`. The counter falling is treated as a reset too, because that is the
sensor-hub case and it happens without the elapsed clock moving.

**The first reading ever credits nothing.** It becomes the baseline. Starting at zero on
install is honest; crediting four days of walking to the afternoon you installed the app is
not.

**An implausible jump is refused but still advances the cursor**, so a single bad reading
cannot be re-offered on every subsequent one.

## Why there is no foreground service

Ply reads the counter on a schedule and lets go, rather than holding a registered listener
open.

Because the value is cumulative, its whole content can be recovered at any moment. There is
nothing to be gained by keeping a process alive, and a lot to lose: a permanent
notification, a process that cannot be killed, and a listener that has to be re-registered
every time the system kills it anyway.

The sensor hub counts with the main CPU asleep whether or not anybody is listening. The app
pays for one sensor callback every fifteen minutes — WorkManager's floor — plus one after
every boot.

## What that costs, stated rather than hidden

**Day totals are exact.** Nothing is lost between readings, however far apart they are,
because the counter carries the total.

**The hourly shape is approximate.** Steps are attributed to the hour they were *read* in,
not the hour they were walked in, so a gap between reads smears across an hour boundary.
Under doze a read can be deferred for hours, and everything walked in that time lands in
one bucket.

**A reboot loses the steps since the last read.** They are gone once the device is off;
nothing holds them. The fifteen-minute period bounds that loss, and
[`BootReceiver`](../app/src/main/java/io/github/sebastianyousef/ply/move/BootReceiver.kt)
closes it from the other side by reading immediately on restart.

If the smearing turns out to matter in practice, the fix is a batched listener registered
while the process happens to be alive — `SensorEvent.timestamp` gives exact per-event
attribution, and the reconciler already accepts a list of readings for exactly that reason.
That would be a change to where the readings come from and not to how they are counted.

## The permission

`ACTIVITY_RECOGNITION` is a runtime permission and the only one the Movement half needs. It
is asked for when that half is first opened rather than at first launch, because a
permission prompt before the app has shown what it is for is a prompt people refuse. Refusing
it costs the Movement half and nothing else.

## What is deliberately not shown

**Distance.** It would be a stride length multiplied by a step count, and the stride length
would be a guess derived from a height the app never asked for. A number computed from two
assumptions is not a measurement.

**Calories.** The same, only more so.
