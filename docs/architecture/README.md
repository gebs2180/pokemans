# Architecture diagrams

Mermaid diagrams of what the code does today, drawn from the code and migrations rather than from [hld.md](../hld.md). Where the two disagree, each page has a "Differences" note; the HLD is the plan, these are the build.

| Page | Covers |
|---|---|
| [01-system-context.md](01-system-context.md) | Who uses the system, the four containers on the VPS, and every outside service (Auth0, R2, EasyPost, TCGdex, Anthropic, pre-graders, Sentry), with which are switched off on prod |
| [02-modules.md](02-modules.md) | Backend module dependencies with import counts, what each module owns (API and tables), the scheduled pollers, and the web app's structure |
| [03-data-model.md](03-data-model.md) | ER diagram of the core tables by module, from Flyway V1–V21 |
| [04-flows.md](04-flows.md) | Sequence diagrams: photo → identification → condition; valuation run and reports; offer → listing → accept → label → shipped → arrived → paid; and the listing/deal state machine |

GitHub renders the diagrams. The review that came with them is [review-architecture-2026-11.md](../review-architecture-2026-11.md).

## Keeping them current

Update a diagram **in the same PR** as a change that makes it wrong: a new module or dependency between modules, a new table or foreign key, a new outside service, a new step or state in a flow, or a new scheduled job. A PR that doesn't touch these needs no diagram change. If you notice one is already wrong, fix it in your PR and say so in the description.

Keep them at this level: modules, tables, endpoints and events, not every class or column.
