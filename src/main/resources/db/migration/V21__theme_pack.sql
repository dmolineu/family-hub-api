-- A theme pack is a look a family imported as one self-contained JSON document
-- (colour tokens plus embedded images). It is family data so every device can
-- pick it and backups carry it; which device shows which look stays on the device.
CREATE TABLE theme_pack (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id UUID NOT NULL,
    name VARCHAR(80) NOT NULL,
    dark BOOLEAN NOT NULL DEFAULT FALSE,
    document TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT now(),
    CONSTRAINT fk_theme_pack_family
        FOREIGN KEY (family_id) REFERENCES family(id) ON DELETE CASCADE
);

CREATE INDEX idx_theme_pack_family ON theme_pack(family_id);
