---
name: review-then-fix
description: Use for a review or audit that will lead to fixes. Findings go in a doc first, then fixes run in fresh sessions.
---

**Phase 1: review.** Write `docs/<kind>-<date>.md`. Number every finding and give its severity, `file:line`, size of fix, and whether it needs Zach's decision.

Reply with the count by severity and the numbered decisions only, then stop. Zach answers by number.

**Phase 2: fix.** Don't fix in the session that reviewed. Group findings by the files they touch, about 1,500 changed lines at most per group, and propose one fresh session per group. Use Sonnet unless the group touches money, auth or valuation math.

Each brief contains:

- The goal in two lines, and the doc path with its finding numbers.
- Up to 8 files to start from, and what not to read.
- Shared files this session owns, and files it must leave alone.
- The tests to run, and what done means.
- Its merge order relative to other open PRs.

Check the 5-hour meter with `get_usage` before starting them. Above 50%, start one and queue the rest. Never run more than two at once.
