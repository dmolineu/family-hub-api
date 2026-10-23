-- A small preview (card colours + a 320x180 thumbnail) derived from the pack at
-- import time so the Appearance picker can draw every pack without downloading
-- the documents. Null for packs imported before this column; filled in on the
-- next listing.
ALTER TABLE theme_pack ADD COLUMN preview TEXT;
