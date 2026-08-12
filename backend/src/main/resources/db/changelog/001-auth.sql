CREATE TABLE users (id UUID PRIMARY KEY, email TEXT NOT NULL, normalized_email TEXT NOT NULL UNIQUE, display_name VARCHAR(100) NOT NULL, password_hash TEXT NOT NULL, preferred_language VARCHAR(5) NOT NULL, role VARCHAR(20) NOT NULL DEFAULT 'AUTHOR', enabled BOOLEAN NOT NULL, verified_at TIMESTAMPTZ);
CREATE TABLE email_verification_tokens (id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id), token_hash BYTEA NOT NULL UNIQUE, expires_at TIMESTAMPTZ NOT NULL, used_at TIMESTAMPTZ, invalidated_at TIMESTAMPTZ);
CREATE TABLE auth_rate_limit_events (id BIGSERIAL PRIMARY KEY, bucket TEXT NOT NULL, bucket_key TEXT NOT NULL, requested_at TIMESTAMPTZ NOT NULL);
CREATE INDEX auth_rate_limit_events_key_time ON auth_rate_limit_events(bucket, bucket_key, requested_at);
CREATE UNIQUE INDEX one_active_email_token ON email_verification_tokens(user_id) WHERE used_at IS NULL AND invalidated_at IS NULL;
