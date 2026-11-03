-- grade_number() was read only by views that V16 dropped; nothing calls it now.
drop function if exists grade_number(text);
