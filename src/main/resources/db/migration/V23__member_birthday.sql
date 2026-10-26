-- A member's birthday: month and day together, the year optional so a parent can keep
-- their age private. Validated in the application (29 February, no future dates).
ALTER TABLE family_member ADD COLUMN birth_month INTEGER;
ALTER TABLE family_member ADD COLUMN birth_day INTEGER;
ALTER TABLE family_member ADD COLUMN birth_year INTEGER;
