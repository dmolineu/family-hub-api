-- Screensaver photos move out of the database into a folder the family chooses (a NAS
-- share, typically, mounted into the container). The database keeps each photo's
-- details and its small thumbnail for the Photos grid; file_name is the photo's path
-- inside the family's folder. Photos uploaded before this keep their bytes in `data`
-- until the app moves them out at startup, and are served from there meanwhile.
ALTER TABLE family ADD COLUMN photo_folder VARCHAR(500);
ALTER TABLE family_photo ADD COLUMN file_name VARCHAR(200);
ALTER TABLE family_photo ALTER COLUMN data DROP NOT NULL;
ALTER TABLE family_photo ADD CONSTRAINT ck_family_photo_stored CHECK (data IS NOT NULL OR file_name IS NOT NULL);
