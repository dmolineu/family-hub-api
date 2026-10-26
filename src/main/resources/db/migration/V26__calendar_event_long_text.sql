-- Events synced from Google keep Google's limits, which are far longer than 255
-- characters for titles, locations (full addresses with directions) and recurrence
-- rules. One long field made a family's first sync fail and import nothing.
-- Native events keep their own shorter limits, enforced by request validation.
ALTER TABLE calendar_event
    ALTER COLUMN title TYPE TEXT,
    ALTER COLUMN location TYPE TEXT,
    ALTER COLUMN recurrence_rule TYPE TEXT;
