ALTER TABLE articles ADD COLUMN created_at TIMESTAMPTZ;
UPDATE articles SET created_at = COALESCE(published_at, deleted_at, NOW()) WHERE created_at IS NULL;
ALTER TABLE articles ALTER COLUMN created_at SET NOT NULL;
