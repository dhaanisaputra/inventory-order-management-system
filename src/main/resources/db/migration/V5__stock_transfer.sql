CREATE TABLE stock_transfer (
  id BIGSERIAL PRIMARY KEY,
  product_id BIGINT NOT NULL REFERENCES product (id),
  from_warehouse_id BIGINT NOT NULL REFERENCES warehouse (id),
  to_warehouse_id BIGINT NOT NULL REFERENCES warehouse (id),
  qty INT NOT NULL CHECK (qty > 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
