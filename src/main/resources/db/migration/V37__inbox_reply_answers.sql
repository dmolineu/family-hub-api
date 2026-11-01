-- A reply the inbox handled (UNDO, or a parent deciding what waits) points at the message
-- its conversation began with, so an answer to an answer still finds what that message
-- added or left waiting.
alter table inbox_message add column answers_id uuid references inbox_message(id) on delete cascade;
