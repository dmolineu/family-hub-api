-- Photos the family uploads for the screensaver. The browser shrinks each one to
-- at most 1920 px and re-encodes it (which also drops location metadata) before
-- upload; the server keeps that file plus a 480 px thumbnail for the Photos grid.
-- Stored in the database so the photo library moves with pg_dump and needs no
-- extra volume. They are not in the JSON backup file, which would balloon.
CREATE TABLE family_photo (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id UUID NOT NULL,
    content_type VARCHAR(20) NOT NULL,
    width INTEGER NOT NULL,
    height INTEGER NOT NULL,
    size_bytes INTEGER NOT NULL,
    data BYTEA NOT NULL,
    thumbnail BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_family_photo_family
        FOREIGN KEY (family_id) REFERENCES family(id) ON DELETE CASCADE,
    CONSTRAINT ck_family_photo_type CHECK (content_type IN ('image/jpeg', 'image/png'))
);

CREATE INDEX idx_family_photo_family_time ON family_photo(family_id, created_at DESC);
