CREATE TABLE password_reset_tokens (id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id), token_hash BYTEA NOT NULL UNIQUE, expires_at TIMESTAMPTZ NOT NULL, used_at TIMESTAMPTZ);
CREATE UNIQUE INDEX one_active_password_reset ON password_reset_tokens(user_id) WHERE used_at IS NULL;
CREATE TABLE email_change_tokens (id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id), new_email TEXT NOT NULL, token_hash BYTEA NOT NULL UNIQUE, expires_at TIMESTAMPTZ NOT NULL, used_at TIMESTAMPTZ);
CREATE UNIQUE INDEX one_active_email_change ON email_change_tokens(user_id) WHERE used_at IS NULL;
