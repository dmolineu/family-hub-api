-- A member's avatar is now a built-in picture key, an https link, or the picture
-- itself as a data URL of up to 64 KB (an uploaded photo or a chosen character copied
-- onto the member), so the column outgrows VARCHAR(255).
ALTER TABLE family_member ALTER COLUMN avatar_url TYPE TEXT;

-- An avatar pack is a set of characters a family imported as one self-contained JSON
-- document (names, groups and inline pictures). It is family data so every device can
-- offer it and backups carry it; choosing a character copies its picture onto the
-- member, so deleting a pack never blanks anyone's avatar.
CREATE TABLE avatar_pack (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id UUID NOT NULL,
    name VARCHAR(80) NOT NULL,
    character_count INT NOT NULL,
    groups TEXT NOT NULL,
    document TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT now(),
    CONSTRAINT fk_avatar_pack_family
        FOREIGN KEY (family_id) REFERENCES family(id) ON DELETE CASCADE
);

CREATE INDEX idx_avatar_pack_family ON avatar_pack(family_id);
