---
name: merge-when-green
description: Use when Zach asks to merge a PR, now or once checks pass.
---

For each PR Zach named, run `gh pr merge <number> --merge --auto` once.

`main` requires `backend`, `web`, `e2e` and `CodeQL`, so GitHub merges when they pass. Don't poll, sleep or run `gh pr checks`. Waiting costs nothing this way.

- Report the PR state in one line: merged, or waiting on checks.
- If a check has already failed, name it and stop. Don't fix it unless Zach asks.
- If the merge is blocked by a conflict, say so. Bring the branch up to date with `sync_with_base_branch`, not `git merge`.
- Update the local `main` checkout only if Zach asked and the PR has merged.
