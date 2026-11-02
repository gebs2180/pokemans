-- Ending someone's access (audit of 2026-11-02, Zach): a membership ends and an invite is revoked by
-- recording who, when and why on its row, never by deleting it. Someone removed can be invited back.
alter table membership drop constraint membership_org_id_user_id_key;
alter table membership
    add column ended_at   timestamptz,
    add column ended_by   uuid references app_user (id),
    add column end_reason text;
create unique index membership_active_once on membership (org_id, user_id) where ended_at is null;

alter table invite
    add column revoked_at timestamptz,
    add column revoked_by uuid references app_user (id);

-- An invite's link is the only thing that counts (Zach, 2026-11-02): the email never bound it.
alter table invite drop column email;
