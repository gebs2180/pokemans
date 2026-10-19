-- Before running more than one instance (follow-ups from the 2026-10-17 review).

-- At most one open prediction request per card and provider, so two instances (or two clicks) can't
-- queue the same paid provider call twice. Left out of V11 because existing rows could break it.

-- Close any duplicates first: keep the newest open request per card and provider, fail the older ones
-- with a reason (rows are kept, never deleted: they are part of the record).
with ranked as (
    select id, row_number() over (partition by card_item_id, provider order by created_at desc, id desc) as rn
    from prediction_request
    where status in ('PENDING', 'SUBMITTED')
)
update prediction_request r
set status      = 'FAILED',
    last_error  = 'Closed by migration V13: a newer open request for this card and provider exists',
    finished_at = now()
from ranked
where r.id = ranked.id
  and ranked.rn > 1;

create unique index prediction_request_open_idx on prediction_request (card_item_id, provider)
    where status in ('PENDING', 'SUBMITTED');

-- A pricing refresh reads every comp in its window in one query, by sold date.
create index sold_comp_sold_on_idx on sold_comp (sold_on);
