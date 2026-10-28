-- A folder of photos the family already has (the NAS Photos share, a phone backup) can
-- join the slideshow beside uploads. It is only read, never written; the app keeps an
-- index of the pictures in it, refreshed daily and whenever the folder changes.
ALTER TABLE family ADD COLUMN photo_source_folder VARCHAR(500);

CREATE TABLE folder_photo (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id UUID NOT NULL,
    -- The picture's path inside the source folder.
    relative_path VARCHAR(1000) NOT NULL,
    size_bytes BIGINT NOT NULL,
    modified_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_folder_photo_family FOREIGN KEY (family_id) REFERENCES family(id) ON DELETE CASCADE,
    CONSTRAINT uq_folder_photo UNIQUE (family_id, relative_path)
);
