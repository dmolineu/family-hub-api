-- Phase 4 (Track C): Google Calendar becomes the source of truth and these tables a
-- durable cache of it, so a Google outage never blanks the wall. Writes go to Google
-- first; the cache is updated from Google's answer.
--
-- This migration only adds. The family's own events stay in calendar_event until the
-- app copies them to Google (that needs an account signed in with the write scope,
-- which a migration cannot do); the copied_* columns record each copy. The old tables
-- go in a later step once the copy has run.

-- One row per Google calendar a family can see, however many accounts see it.
create table google_calendar (
    id                 uuid primary key default gen_random_uuid(),
    family_id          uuid         not null references family(id) on delete cascade,
    google_calendar_id varchar(1024) not null,
    summary            text,
    color              varchar(16),
    time_zone          varchar(100),
    -- The account that polls this calendar; another account that sees it can take over.
    sync_token_id      uuid references google_oauth_token(id) on delete set null,
    sync_token         text,
    last_synced_at     timestamptz,
    last_sync_error    text,
    full_sync_required boolean      not null default true,
    enabled            boolean      not null default true,
    created_at         timestamptz  not null default now(),
    constraint uq_google_calendar unique (family_id, google_calendar_id)
);

-- What each signed-in account may do on a calendar (Google's calendarList accessRole).
create table google_calendar_access (
    calendar_id uuid        not null references google_calendar(id) on delete cascade,
    token_id    uuid        not null references google_oauth_token(id) on delete cascade,
    access_role varchar(20) not null,
    primary key (calendar_id, token_id)
);

-- Whose lane an event on this calendar goes in when the event names no members itself.
-- Only that: where new events are written is the family's default calendar below.
create table google_calendar_member (
    calendar_id uuid not null references google_calendar(id) on delete cascade,
    member_id   uuid not null references family_member(id) on delete cascade,
    primary key (calendar_id, member_id)
);

alter table family
    add column default_google_calendar_id uuid references google_calendar(id) on delete set null;

-- The cache: masters, edited or cancelled instances and single events, as Google
-- returns them. raw keeps Google's whole event (its JSON) so an update sends back the
-- fields the app does not model (reminders, colours, conference links). It is text, not
-- jsonb: nothing queries inside it, and text maps the same way on the dev profile's H2.
create table google_event (
    id                  uuid primary key default gen_random_uuid(),
    calendar_id         uuid          not null references google_calendar(id) on delete cascade,
    google_event_id     varchar(1024) not null,
    ical_uid            varchar(1024),
    etag                varchar(255),
    status              varchar(20)   not null,
    title               text,
    description         text,
    location            text,
    html_link           varchar(2048),
    all_day             boolean       not null,
    -- Timed events.
    start_at            timestamptz,
    end_at              timestamptz,
    -- All-day events; end_date is Google's exclusive end.
    start_date          date,
    end_date            date,
    time_zone           varchar(100),
    -- RRULE/EXDATE/RDATE lines, verbatim.
    recurrence          text[],
    -- Google's id of the master, as a string: instances may arrive before it.
    recurring_event_id  varchar(1024),
    original_start_at   timestamptz,
    original_start_date date,
    organizer_email     varchar(320),
    organizer_self      boolean       not null default false,
    member_ids          uuid[],
    google_updated_at   timestamptz,
    raw                 text          not null,
    constraint uq_google_event unique (calendar_id, google_event_id)
);

create index idx_google_event_timed on google_event (calendar_id, start_at, end_at);
create index idx_google_event_all_day on google_event (calendar_id, start_date, end_date);
create index idx_google_event_master on google_event (calendar_id, recurring_event_id);

alter table google_oauth_token
    add column google_account_email varchar(320),
    add column needs_reauth         boolean not null default false;

-- The inbox points at an event by Google's ids, which survive the cache being rebuilt.
alter table inbox_import
    add column google_calendar_id uuid references google_calendar(id) on delete set null,
    add column google_event_id    varchar(1024);

alter table calendar_event
    add column copied_calendar_id     uuid references google_calendar(id) on delete set null,
    add column copied_google_event_id varchar(1024),
    add column copied_at              timestamptz;

-- Carry over what families already sync. Two members syncing one calendar share a row;
-- it is enabled if either had it on, and polled with the token of an enabled copy.
insert into google_calendar (family_id, google_calendar_id, summary, sync_token_id, enabled)
select distinct on (m.family_id, s.google_calendar_id)
       m.family_id,
       s.google_calendar_id,
       s.calendar_name,
       s.token_id,
       bool_or(s.enabled) over (partition by m.family_id, s.google_calendar_id)
from google_synced_calendar s
         join family_member m on m.id = s.member_id
order by m.family_id, s.google_calendar_id, s.enabled desc, s.created_at;

-- Unmarked events stay in the lane they are shown in today: the member who synced them.
insert into google_calendar_member (calendar_id, member_id)
select distinct c.id, s.member_id
from google_synced_calendar s
         join family_member m on m.id = s.member_id
         join google_calendar c on c.family_id = m.family_id and c.google_calendar_id = s.google_calendar_id;

-- A family that syncs exactly one enabled calendar writes new events there; any other
-- family picks its default in Family Settings.
update family f
set default_google_calendar_id = only_one.id
from (select family_id, min(id::text)::uuid as id
      from google_calendar
      where enabled
      group by family_id
      having count(*) = 1) only_one
where f.id = only_one.family_id;
