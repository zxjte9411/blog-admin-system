CREATE TABLE tags (id UUID PRIMARY KEY, name VARCHAR(100) NOT NULL CHECK (name = btrim(name)));
CREATE UNIQUE INDEX tags_name_ci_unique ON tags (lower(name));
CREATE TABLE articles (
  id UUID PRIMARY KEY,
  owner_id UUID NOT NULL REFERENCES users(id),
  author_attribution VARCHAR(100) NOT NULL,
  title VARCHAR(200) NOT NULL,
  content TEXT NOT NULL,
  status VARCHAR(20) NOT NULL,
  published_at TIMESTAMPTZ,
  deleted_at TIMESTAMPTZ,
  version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE article_tags (article_id UUID NOT NULL REFERENCES articles(id), tag_id UUID NOT NULL REFERENCES tags(id), PRIMARY KEY(article_id, tag_id));
CREATE INDEX articles_status_idx ON articles(status, published_at);
