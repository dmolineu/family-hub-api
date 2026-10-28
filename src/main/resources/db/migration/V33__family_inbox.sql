-- The family inbox: every message it has handled (so a message is never handled
-- twice), the extra addresses a family lets send to it, and what each message added.

create table inbox_message (
    id           uuid primary key,
    family_id    uuid not null references family(id) on delete cascade,
    -- The message's own Message-ID header (RFC 5322 allows up to 998 characters).
    message_id   varchar(998) not null,
    sender       varchar(320),
    subject      varchar(998),
    outcome      varchar(20)  not null,
    detail       varchar(1000),
    -- The confirmation the inbox sent back, so a reply to it ("UNDO") finds this message.
    reply_message_id varchar(998),
    processed_at timestamptz  not null,
    constraint uq_inbox_message unique (family_id, message_id)
);

create index idx_inbox_message_reply on inbox_message (family_id, reply_message_id);

create table inbox_sender (
    id         uuid primary key,
    family_id  uuid not null references family(id) on delete cascade,
    address    varchar(320) not null,
    created_at timestamptz  not null,
    constraint uq_inbox_sender unique (family_id, address)
);

create table inbox_import (
    id                uuid primary key,
    family_id         uuid not null references family(id) on delete cascade,
    message_id        uuid not null references inbox_message(id) on delete cascade,
    kind              varchar(10)  not null,
    status            varchar(10)  not null,
    title             varchar(200) not null,
    -- What the reader proposed, as JSON, for an import still waiting for review.
    details           text,
    confidence        varchar(10),
    calendar_event_id uuid,
    photo_id          uuid,
    recipe_id         uuid,
    created_at        timestamptz  not null,
    decided_at        timestamptz,
    decided_by        uuid
);

create index idx_inbox_import_family on inbox_import (family_id, created_at desc);
