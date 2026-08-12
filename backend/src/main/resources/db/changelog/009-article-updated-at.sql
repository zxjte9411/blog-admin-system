ALTER TABLE articles ADD COLUMN updated_at TIMESTAMPTZ;
UPDATE articles SET updated_at = COALESCE(published_at, created_at, NOW()) WHERE updated_at IS NULL;
ALTER TABLE articles ALTER COLUMN updated_at SET NOT NULL;
