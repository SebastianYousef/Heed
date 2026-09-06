Read CLAUDE.md first, then do the following. Be efficient with tokens throughout —
filter command output, read files in slices, and offer me a fresh-session handoff prompt
before quality degrades rather than pushing on.

## Context

`Keel/` holds two Android apps by me, Sebastian Yousef, sole contributor:

- `Ply/` — a training log and step counter, 0.1.0, working and installed on my phone.
  It also contains the shared UI/base module `:keel` that both apps will use.
- `Heed/` — a notification filter and attention tracker, already public at
  https://github.com/SebastianYousef/Heed. Not yet ported to the base.

Ply has 8 local commits and has never been pushed anywhere.

## What I want done in this session

**1. Sort out the GitHub situation, and tell me the options before you act.**

Ply needs a home. Decide with me between:
  (a) a new repository for Ply alone, with `keel` living inside it as a module;
  (b) a new `Keel` repository holding both apps, with Heed moved in — its history is
      worth preserving, and its existing GitHub URL should keep working if possible;
  (c) renaming the existing `Heed` repository to `Keel` and restructuring in place, which
      preserves stars, issues, history and gives a URL redirect for free.

Lay out the trade-offs in a few lines each, recommend one, and wait for my answer before
touching any remote. Do not force-push, rewrite published history, or delete a remote
repository without asking me in the same breath.

**2. Licensing must match Heed exactly.** GPL-3.0-or-later, the verbatim `LICENSE` file,
copyright Sebastian Yousef, and the README's licence section written the same way Heed's
is. If both apps end up in one repository, make sure the licence covers both clearly and
that neither app's README loses its own statement.

**3. The README is for someone deciding whether to install.** What the app does, how to
get it, what it will and will not do to their data — and nothing else. All the reasoning
lives in `docs/`, with an index, exactly as Heed does it. Ply already has this shape; check
it reads well cold, to a stranger, and fix it where it assumes context. If a `Keel`
repository is created, it needs a top-level README explaining the family and the shared
base without duplicating either app's.

**4. Keep the conservative versioning.** Ply stays 0.1.0 and stays in the 0.x line for a
long time — a version number is a claim about how settled something is, and I spent 1.0
too early on Heed. Do not bump anything to 1.0. If Heed is restructured, consider whether
its version should be walked back in `versionName` (its `versionCode` cannot decrease).

**5. Push it, and confirm what is public.** Before the first push, check that no keystore,
no `keystore.properties`, no `local.properties` and no `.jks` is tracked, and that
`.gitignore` covers them. After pushing, tell me the URL and what a stranger now sees.

## After that

Come back to me before starting new feature work. The open gaps are in
`Ply/docs/status.md` — measurements, custom exercises, import, and porting Heed onto the
shared base are the main ones, in roughly that order of appetite.
