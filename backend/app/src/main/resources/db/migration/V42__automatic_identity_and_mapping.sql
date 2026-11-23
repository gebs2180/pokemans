-- Automatic identity confirmation and automatic PokeTrace mapping (2026-11-22, docs/offers.md "Automatic gates").
-- Both are off by default and both are "defaults to confirm" in the methodology doc.

-- An identity the system confirmed: the collector confirmed the reader's top suggestion, its confidence cleared the
-- threshold, and edition, finish and stamp were settled. It records which rule version decided it and which proposal
-- and suggestion it came from (the reading is reading_id). The admin overrides it like any identification, by adding a
-- newer REVIEWER row. No foreign keys on the two ids: forget_cards deletes proposals before identifications, and these
-- rows are append-only, so a reference couldn't be cleared.
alter table card_identification drop constraint card_identification_source_check;
alter table card_identification add constraint card_identification_source_check
    check (source in ('REVIEWER', 'PROVIDER', 'SYSTEM'));
alter table card_identification add column rule_version text;
alter table card_identification add column proposal_id uuid;
alter table card_identification add column suggestion_id uuid;
alter table card_identification add constraint card_identification_system check (
    case when source = 'SYSTEM'
        then status = 'CONFIRMED' and created_by is null and rule_version is not null and proposal_id is not null
            and suggestion_id is not null and reading_id is not null
        else rule_version is null and proposal_id is null and suggestion_id is null
    end);

-- A mapping the system made: exactly one PokeTrace candidate agreed with the card on everything checked. It has no
-- user behind it, so mapped_by is null and rule_version says which rule made it. It never carries an override reason:
-- a disagreeing candidate is never mapped automatically.
alter table poketrace_card alter column mapped_by drop not null;
alter table poketrace_card add column automatic boolean not null default false;
alter table poketrace_card add column rule_version text;
alter table poketrace_card add constraint poketrace_card_automatic check (
    case when automatic
        then mapped_by is null and rule_version is not null and poketrace_id is not null and override_reason is null
        else mapped_by is not null and rule_version is null
    end);
