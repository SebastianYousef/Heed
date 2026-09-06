# The logging screen, and what a set costs

Everything else in Ply is secondary to one number: how many times you touch the phone to
record a set, standing in a gym, between two of them.

The answer is **one tap** for the common case. This page is the argument for that number
and the account of where every other tap went.

## The count

| What you are recording | Taps |
|---|---|
| The same as the last set | **1** |
| Same weight, one more or one fewer rep | 2 |
| One increment heavier, same reps | 2 |
| Two increments heavier | 3 |
| A weight far from the last one | 3–4 |
| A set of a different exercise | 2 + choosing it |

The first row is most of them. People repeat sets — that is what a set *is* — and an app
that does not make repeating free is charging for its most common operation.

## Why it is one and not three

The screen holds a stepper for weight, a stepper for reps, and a log button. When an
exercise is put on screen both steppers are already filled in, from this, in order:

1. **What you have already done for this exercise in this session.** Right nearly always.
2. **The routine's target**, if the session came from one.
3. **What you did last time you trained it.**
4. 0 kg × 8, reached only for an exercise you have never done that no routine asked for.

So the common path is: look, confirm it is right, press Log. The set is written, the rest
timer starts itself, and the row appears above with the next set number already on the
button.

## What was taken off this screen, and why

**The keyboard.** Every app in this category makes the weight a text field. That means
summoning a keyboard that covers half the screen, waiting for it, clearing the old value,
typing, and dismissing it — six or seven interactions to say "the same as last time but
five kilos more". The stepper is the whole difference, and it is why the number is a
stepper even though a field would be less code.

The value is still tappable for a large jump, which is the case a stepper genuinely loses:
going from 20 kg to 100 kg is thirty-two presses. That path costs three or four taps, and
it is rare, which is the right place to put the cost.

**Confirmation.** Logging is immediate and undoable rather than deferred and confirmed. A
dialog asking whether you meant it charges a tap on every set to save one on the rare
wrong one, and the row is one tap to delete.

**RPE.** It is offered on the row *after* the set exists, not in the entry controls. A
field between a person and the log button is a field that stops them logging. Afterwards,
ignoring it forever costs nothing.

**The rest timer prompt.** It starts itself. "Start a timer?" after every set is a second
tap on every set to serve the minority of sets you did not want one for; the switch to turn
that off is in settings, where a decision made once belongs.

**Set-type pickers, tempo, bar selection, notes.** None of them are on the path. The
warm-up toggle is the single exception, because logging a warm-up as a working set corrupts
volume and records rather than merely being untidy — and it switches itself back off after
one set, because leaving it on is how the first three working sets get logged as warm-ups.

## The rule for anything added later

Any new control on this screen must either not sit between the person and the log button,
or come with an argument for why it is worth a tap on **every** set. Most features that
want to be here are worth a tap on some sets and none on the rest, and those belong on the
row afterwards, in the exercise's own screen, or in settings.
