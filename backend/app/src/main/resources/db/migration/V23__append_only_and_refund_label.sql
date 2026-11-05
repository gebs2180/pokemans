-- Append-only claims the schema didn't enforce (review findings 40 and 56).

-- A person's centering reading is append-only (V19 says so): labelling again adds a row.
-- refuse_update() is from V9. Deletes stay possible, for data-deletion requests.
create trigger centering_label_append_only before update on centering_label
    for each row execute function refuse_update();

-- A condition assessment is a version: a new one is a new row. The only change allowed is the old
-- current version stepping down (is_current true to false) when a new one takes its place.
create function condition_assessment_steps_down_only() returns trigger language plpgsql as $$
begin
    if (to_jsonb(new) - 'is_current') is distinct from (to_jsonb(old) - 'is_current')
        or (new.is_current and not old.is_current) then
        raise exception 'condition_assessment is append-only: only the current version may step down';
    end if;
    return new;
end;
$$;

create trigger condition_assessment_steps_down_only before update on condition_assessment
    for each row execute function condition_assessment_steps_down_only();

-- A refund names its label and its listing; the two must be the label's own listing.
alter table shipping_label add constraint shipping_label_id_listing_unique unique (id, listing_id);
alter table label_refund add constraint label_refund_label_listing_fk
    foreign key (label_id, listing_id) references shipping_label (id, listing_id);
