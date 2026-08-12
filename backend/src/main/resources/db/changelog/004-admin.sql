CREATE TABLE admin_invitations (id UUID PRIMARY KEY, email TEXT NOT NULL, token_hash BYTEA NOT NULL UNIQUE, expires_at TIMESTAMPTZ NOT NULL, used_at TIMESTAMPTZ);
CREATE INDEX admin_invitations_email ON admin_invitations(email);
CREATE UNIQUE INDEX one_pending_admin_invitation ON admin_invitations(email) WHERE used_at IS NULL;
CREATE TABLE password_setting_changes (id UUID PRIMARY KEY, operator_id UUID NOT NULL REFERENCES users(id), previous_value INTEGER NOT NULL, new_value INTEGER NOT NULL, changed_at TIMESTAMPTZ NOT NULL);
CREATE TABLE password_settings (id BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (id), minimum_length INTEGER NOT NULL CHECK (minimum_length BETWEEN 8 AND 128));
INSERT INTO password_settings (id, minimum_length) VALUES (TRUE, 8);
