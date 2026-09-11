CREATE TABLE purchase_order (
  id BIGSERIAL PRIMARY KEY,
  status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE purchase_order_line (
  id BIGSERIAL PRIMARY KEY,
  purchase_order_id BIGINT NOT NULL REFERENCES purchase_order (id),
  product_id BIGINT NOT NULL REFERENCES product (id),
  ordered_qty INT NOT NULL CHECK (ordered_qty > 0),
  received_qty INT NOT NULL DEFAULT 0 CHECK (received_qty >= 0),
  CONSTRAINT uq_po_line_product UNIQUE (purchase_order_id, product_id)
);

CREATE TABLE return_order (
  id BIGSERIAL PRIMARY KEY,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE return_line (
  id BIGSERIAL PRIMARY KEY,
  return_order_id BIGINT NOT NULL REFERENCES return_order (id),
  allocation_id BIGINT NOT NULL REFERENCES allocation (id),
  qty INT NOT NULL CHECK (qty > 0)
);
