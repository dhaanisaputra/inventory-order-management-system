CREATE TABLE order_idempotency (
  idem_key VARCHAR(64) PRIMARY KEY,
  request_hash VARCHAR(64) NOT NULL,
  order_id BIGINT NOT NULL REFERENCES sales_order (id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
