# Claude instructions for this repository

This repository ("Nuvio RS") is a fork of upstream Nuvio
(`NuvioMedia/NuvioMobile`). It exists to carry a small set of
fork-specific changes on top of upstream, not to diverge from it.

## Upstream compatibility is the top priority

Every change made in this repo, by an agent or a human, must be written
so that it can keep tracking upstream with minimal friction. Concretely:

- **Minimize the diff against upstream.** Prefer the smallest change that
  achieves the goal over a more "correct" or "elegant" rewrite. Fewer
  touched files and fewer touched lines is better, even if it means the
  change is less idiomatic.
- **Do not restructure or refactor upstream code** to fit a change in.
  Do not rename, move, or reorganize existing files, modules, classes, or
  functions unless the task explicitly requires it. Avoid renaming
  variables/functions in code you're touching for style reasons.
- **Keep fork-specific logic detached from upstream architecture.**
  Where possible, add new code in new files, behind clearly-named
  fork-specific hooks, flags, or thin wrapper layers, rather than
  weaving new logic into the middle of existing upstream functions.
  This keeps future `git merge`/rebase from upstream low-conflict.
- **Avoid dependency and architecture changes.** Do not add new
  dependencies, change build tooling, or alter the module/package
  structure unless the task explicitly calls for it. These are exactly
  the kinds of changes that create merge conflicts with upstream.
- **No opportunistic cleanups.** Don't reformat, reorder imports, "fix"
  unrelated code, or bundle drive-by changes into a fork-specific PR.
  Unrelated diffs make future upstream merges harder to reason about.
- **Prefer additive over subtractive/destructive changes.** Favor
  feature flags, config, or conditional branches over deleting or
  rewriting upstream behavior, unless the task is specifically to remove
  or replace that behavior.
- **When a change must touch shared/upstream code**, keep the touch as
  localized and surgical as possible (e.g. a small conditional, a single
  extra parameter) rather than restructuring the surrounding function.

## Never trade away accuracy, performance, or reliability

Minimal-diff and upstream-compatibility are how a change is written, not
an excuse for what it does. No implementation may sacrifice accuracy,
performance, or reliability for the sake of a smaller diff or easier
merge:

- **Accuracy**: the feature must behave correctly, including edge cases
  it's reasonably expected to handle. Don't ship a simplified or
  approximate implementation just because the precise one would touch
  more code.
- **Performance**: don't introduce regressions (extra work on hot paths,
  unnecessary recomposition/allocations, blocking calls on the UI/main
  thread, etc.) to keep a diff small. A slightly larger, detached
  fork-specific implementation that performs well beats a tiny shim that
  doesn't.
- **Reliability**: don't skip error handling, race-condition safety, or
  proper state management to save lines. Fork-specific code must be as
  robust as the upstream code it sits next to.

When minimal-diff/detachment and accuracy/performance/reliability are in
tension, keep the change detached from upstream architecture (new files,
thin hooks, wrappers) but do not shrink its correctness, speed, or
robustness to achieve that detachment.

## Practical checklist before finishing a change

- Would this diff still apply cleanly (or with trivial conflicts) if
  upstream changed unrelated parts of the same file?
- Could this have been done in a new, fork-owned file instead of editing
  an upstream one?
- Is there any unrelated refactor, rename, or reformat mixed into this
  change? If so, remove it.
- Does this add a dependency or change architecture that isn't strictly
  required by the task?

If in doubt, choose the option that is smaller and easier to unmerge or
rebase away from later — this fork should always be able to re-sync with
`NuvioMedia/NuvioMobile` with as little friction as possible.

See `CONTRIBUTING.md` for the additional upstream project rules on PR
scope (bug fixes only, no unapproved features/refactors/UI changes,
etc.) — those still apply on top of the above.
