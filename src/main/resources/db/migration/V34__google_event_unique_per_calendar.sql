-- A Google event shared by two calendars (an invitation, a family calendar the
-- event was copied to) keeps the same event id in both. The id was unique across
-- the whole table, so the second calendar's sync failed on every run once the first
-- one held the event. Unique per synced calendar instead; the index also serves the
-- per-calendar lookups and deletes the sync makes.
DROP INDEX IF EXISTS idx_calendar_event_google_id;

CREATE UNIQUE INDEX idx_calendar_event_calendar_google_id
    ON calendar_event (synced_calendar_id, google_event_id)
    WHERE google_event_id IS NOT NULL;

-- A synced calendar's row goes away when its member reconnects Google (the old
-- token and, through it, its calendars are deleted). With SET NULL its events stayed
-- behind with no calendar that would ever sync or remove them; with the per-calendar
-- index above they would also have doubled up on the next import. They go with it.
ALTER TABLE calendar_event
    DROP CONSTRAINT calendar_event_synced_calendar_id_fkey,
    ADD CONSTRAINT calendar_event_synced_calendar_id_fkey
        FOREIGN KEY (synced_calendar_id) REFERENCES google_synced_calendar (id) ON DELETE CASCADE;
