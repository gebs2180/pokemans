# Known pitfalls

Mistakes sessions here have made or nearly made, and how to avoid them. The orchestrator pastes the section below into every session brief, as written.

**Upkeep.** When a session hits a new pitfall, add it in the PR that fixes it. When a rule stops being true, for example because CI now catches it, delete it. Keep about 6–10 items. Rules already in CLAUDE.md or a skill stay there and are not repeated here.

## Known pitfalls

- **Migration numbers collide after main moves.** Two branches both took the next Flyway number, so one had to be renumbered after rebasing (V17 on 2026-10-19, V31 on 2026-11-11). Check the highest `V__` on `origin/main` just before opening the PR, and renumber if main has moved.
- **Changing UI copy breaks the e2e tests.** Renamed buttons and labels broke Playwright assertions three times (2026-11-03, 2026-11-07, 2026-11-23). When you change user-facing text, grep `e2e/` for the old string and update it in the same PR.
- **The e2e tests share one database between phone and desktop.** Asserting exact counts or "the only card" fails when the other viewport's run adds rows (2026-11-03). Assert "at least N", or find your own card by something unique.
- **CodeQL is a required check.** It blocked a photo preview built from an object URL in an `img` (2026-11-23). Prefer patterns it doesn't flag over suppressing it, and expect it to run on web changes.
- **Skipped tests aren't passing tests.** With Docker off, the Testcontainers tests are skipped, not failed: #190 reported 400 passed with 233 skipped (2026-11-24). Report the skipped count as "not run locally", and don't call the work verified until CI has run them.
- **Auto-merge can land a PR before its review fixes.** #190 had auto-merge on while review findings were still open (2026-11-24). Run `/code-review` before enabling auto-merge. If fixes come after, turn it off with `gh pr merge N --disable-auto` until they're pushed.
- **A worktree-isolated session refuses compound git commands that target another folder.** Chained `cd … && git …` into another worktree was refused (2026-11-24). Run plain commands one at a time with `git -C <path>`, and edit files with the Edit and Write tools.
- **Photos and secrets never go through git or chat.** Testers' photos (the friend's included) stay out of the repo and out of test fixtures. Secrets live in Bitwarden: a session never asks for one in chat, never prints `deploy/.env`, and never writes one into a committed file.
