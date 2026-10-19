-- From the 2026-10-17 review: indexes on lookup and foreign-key columns, and the database refusing
-- updates on the tables the code already treats as append-only (calibration and audit evidence).

create index card_identification_reading_idx on card_identification (reading_id);
create index card_identification_catalog_card_idx on card_identification (catalog_card_id);
create index provider_prediction_request_idx on provider_prediction (request_id);
create index sold_comp_ingest_run_idx on sold_comp (ingest_run_id);
create index price_stats_card_idx on price_stats (catalog_card_id, as_of);

-- refuse_update() is from V9. Deletes stay possible, for data-deletion requests.
create trigger provider_prediction_append_only before update on provider_prediction
    for each row execute function refuse_update();
create trigger consent_record_append_only before update on consent_record
    for each row execute function refuse_update();
create trigger grade_outcome_append_only before update on grade_outcome
    for each row execute function refuse_update();
create trigger outcome_verification_append_only before update on outcome_verification
    for each row execute function refuse_update();
create trigger card_identification_append_only before update on card_identification
    for each row execute function refuse_update();
