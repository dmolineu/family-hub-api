-- Each member's own look, which a screen switches to when they are tapped: null is
-- the screen's own look. And how long, untouched, a screen keeps someone's look
-- before going back to its own: 0 means it never goes back by itself.
ALTER TABLE family_member ADD COLUMN theme VARCHAR(100);
ALTER TABLE family ADD COLUMN theme_revert_minutes INTEGER NOT NULL DEFAULT 0;
