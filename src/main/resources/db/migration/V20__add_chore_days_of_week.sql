-- A weekly chore may be due on specific days ("MONDAY,THURSDAY"); each such day is
-- its own one-day period. Null keeps the once-a-week-any-day meaning.
ALTER TABLE chore_template ADD COLUMN days_of_week VARCHAR(70);
