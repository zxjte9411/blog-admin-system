CREATE TABLE user_identities (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES users(id),
  provider VARCHAR(20) NOT NULL,
  subject TEXT NOT NULL,
  UNIQUE (provider, subject),
  UNIQUE (user_id, provider)
);
