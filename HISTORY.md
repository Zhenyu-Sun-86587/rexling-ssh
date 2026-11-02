# Repository history

Rexlink combines the original `rex-probe` and `rex-android` Git histories.
The migration retains the original commits and their IDs; it does not squash or
filter-rewrite either source history. The merge commit connects both ancestries.
Source-head IDs and migration details are recorded in `MIGRATION.json`.

- `legacy/probe` identifies the imported probe history.
- `legacy/android` identifies the imported Android history.
- New commits record pending Android work, consolidation, build-path updates,
  documentation and Apache 2.0 licensing separately.
- Original directories remain intact as a rollback/reference until you choose
  to retire them. The new build has no dependency on either old checkout.

```sh
git log --all --graph --decorate --oneline
git log legacy/android --oneline
git log legacy/probe --oneline
git log --follow -- rexlink-core/src/control.rs
git log --follow -- rexlink-android/app/src/main/java/dev/shulhi/rex/MainActivity.kt
```

Git follows individual file renames heuristically. The explicit legacy branches
always expose full pre-migration history even if a directory-level log does not
cross the merge automatically. There are no submodules or nested `.git` repos.
Nothing was pushed to a remote service by this migration.
