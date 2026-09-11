CREATE TABLE outbox (
  id BIGSERIAL PRIMARY KEY,
  topic VARCHAR(128) NOT NULL,
  event_key VARCHAR(128) NOT NULL,
  payload TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_at TIMESTAMPTZ
);
CREATE INDEX ix_outbox_unpublished ON outbox (id) WHERE published_at IS NULL;
