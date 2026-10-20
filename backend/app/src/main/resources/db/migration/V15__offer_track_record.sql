-- The instant-offer track record (Zach, 2026-10-19): what happened to every offer we posted, as
-- read-only views for the analysis workspace and the admin's "Offer track record" page. The realized
-- sales become the evidence for the lender and insurer pitch, so nothing here flatters: every row is
-- kept, and the calibration view says where each grade came from.
--
-- Grades are numbers, 0 for ungradable (U). A stored grade key is "U", "G9" or "9" depending on how it
-- was written; grade_number() reads all three.

create function grade_number(grade_key text) returns int
    language sql immutable strict
    as $$ select case when grade_key in ('U', 'G0') then 0 else ltrim(grade_key, 'G')::int end $$;

-- One row per listing (an offer posted to buyers). A card posted three times is three rows.
--
--   predicted_median   lower median of the condition distribution the offer was priced on; null for a
--                      verified graded card, which had no prediction
--   stress_shift       grades the FLV stress shifts that distribution down: from the offer's trace,
--                      else the parameter default (3 at confidence C, else 2)
--   stressed_median    the median after that shift: floored at 1, U stays U (the shift is monotone, so
--                      the median of the shifted distribution is the shifted median)
--   status             OPEN, ACCEPTED, WITHDRAWN or EXPIRED as of now
--   inspection_source  BUYER_INSPECTION when the buyer recorded an inspection. Buyers grade the card
--                      they are paying for, so they have a reason to grade low: never pool it with
--                      graders' results
--   ladder_price_at_inspected  what the offer said for the grade found (half grades fold down)
create view v_offer_outcomes as
select l.id                                                        as listing_id,
       o.id                                                        as offer_id,
       l.card_item_id,
       l.org_id                                                    as seller_org_id,
       o.catalog_card_id,
       o.inputs -> 'identity' ->> 'name'                           as card_name,
       o.inputs -> 'identity' ->> 'setName'                        as set_name,
       o.inputs -> 'identity' ->> 'number'                         as card_number,
       o.inputs -> 'identity' ->> 'language'                       as language,
       o.inputs -> 'identity' ->> 'edition'                        as edition,
       o.inputs -> 'identity' ->> 'variant'                        as variant,
       o.graded,
       o.confidence,
       o.subject_to_authentication,
       o.parameter_set,
       (o.inputs -> 'prices' ->> 'salesPerMonth')::numeric         as sales_per_month,
       o.created_at                                                as offered_at,
       o.expected_offer,
       o.fmv,
       l.created_at                                                as listed_at,
       l.expires_at                                                as listing_expires_at,
       case
           when acc.id is not null then 'ACCEPTED'
           when wd.id is not null then 'WITHDRAWN'
           when l.expires_at > now() then 'OPEN'
           else 'EXPIRED'
       end                                                         as status,
       acc.id is not null                                          as accepted,
       acc.created_at                                              as accepted_at,
       acc.actor_org_id                                            as buyer_org_id,
       extract(epoch from acc.created_at - l.created_at) / 3600.0  as hours_to_accept,
       wd.id is not null                                           as withdrawn,
       wd.created_at                                               as withdrawn_at,
       acc.id is null and wd.id is null and l.expires_at <= now()  as expired,
       med.predicted_median,
       shift.stress_shift,
       case
           when med.predicted_median is null then null
           when med.predicted_median = 0 then 0
           else greatest(1, med.predicted_median - shift.stress_shift)
       end                                                         as stressed_median,
       case when insp.id is not null then 'BUYER_INSPECTION' end   as inspection_source,
       insp.created_at                                             as inspected_at,
       insp.inspected_grade,
       insp.authentic,
       insp.declined_reason,
       insp.price_paid,
       rung.price                                                  as ladder_price_at_inspected
from sale_listing l
         join card_offer o on o.id = l.offer_id
         left join listing_event acc on acc.listing_id = l.id and acc.type = 'ACCEPTED'
         left join listing_event wd on wd.listing_id = l.id and wd.type = 'WITHDRAWN'
         left join listing_event insp on insp.listing_id = l.id and insp.type = 'INSPECTED'
         left join lateral (
    select min(g) as predicted_median
    from (select g, sum(p) over (order by g) as cumulative
          from (select grade_number(d.key) as g, d.value::numeric as p
                from jsonb_each_text(o.inputs -> 'assessment' -> 'distribution') d) grades) running
    where not o.graded and cumulative >= 0.5
    ) med on true
         left join lateral (
    select coalesce(
                   (select substring(s ->> 'rule' from 'shifted ([0-9]+) grades')::int
                    from jsonb_array_elements(o.trace) s
                    where s ->> 'subject' = 'FMV_stressed'
                    limit 1),
                   case when o.confidence = 'C' then 3 else 2 end) as stress_shift
    ) shift on true
         left join lateral (
    select (r ->> 'price')::numeric as price
    from jsonb_array_elements(o.ladder) r
    where insp.inspected_grade is not null and grade_number(r ->> 'grade') = floor(insp.inspected_grade)::int
    limit 1
    ) rung on true;

-- Predicted against inspected grade, one row per inspection of a raw card, with its source kept.
-- Left out: graded cards (nothing was predicted) and cards declined as not genuine or not the listed
-- card (the prediction was about a different card). Cards declined as not matching their photos stay
-- in: that can only lower the hit rate. When graders' results for sold cards arrive they join here
-- with their own source, never relabelled.
--
--   grade_difference  inspected minus predicted median, on the inspected (unfolded) grade; negative
--                     means the buyer found it worse than we predicted
--   within_one        |inspected - predicted median| <= 1, on the unfolded grade, so a 7.5 against a
--                     predicted 9 is a miss
--   below_stressed    inspected below the stressed median
create view v_offer_grade_calibration as
select listing_id,
       card_item_id,
       catalog_card_id,
       buyer_org_id,
       inspection_source                                   as source,
       inspected_at,
       confidence,
       predicted_median,
       stress_shift,
       stressed_median,
       inspected_grade,
       declined_reason,
       inspected_grade - predicted_median                  as grade_difference,
       abs(inspected_grade - predicted_median) <= 1        as within_one,
       inspected_grade < stressed_median                   as below_stressed
from v_offer_outcomes
where inspection_source is not null
  and not graded
  and predicted_median is not null
  and (declined_reason is null or declined_reason = 'DOES_NOT_MATCH_PHOTOS');
