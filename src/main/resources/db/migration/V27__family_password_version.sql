-- Bumped every time the family's sign-in password changes (in the app or with the
-- server's reset-password command). Each sign-in token carries the version it was
-- issued under, so a change signs out every other device at once instead of
-- leaving their 24-hour tokens valid. Tokens issued before this column existed
-- carry no version and count as 0.
ALTER TABLE family
    ADD COLUMN password_version INT NOT NULL DEFAULT 0;
