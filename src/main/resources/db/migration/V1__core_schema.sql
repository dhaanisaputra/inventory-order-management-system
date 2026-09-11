CREATE TABLE product (
  id BIGSERIAL PRIMARY KEY,
  sku VARCHAR(64) NOT NULL UNIQUE,
  name VARCHAR(255) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE warehouse (
  id BIGSERIAL PRIMARY KEY,
  code VARCHAR(64) NOT NULL UNIQUE,
  name VARCHAR(255) NOT NULL,
  priority INT NOT NULL DEFAULT 100,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE inventory (
  id BIGSERIAL PRIMARY KEY,
  product_id BIGINT NOT NULL REFERENCES product (id),
  warehouse_id BIGINT NOT NULL REFERENCES warehouse (id),
  available INT NOT NULL DEFAULT 0 CHECK (available >= 0),
  reserved INT NOT NULL DEFAULT 0 CHECK (reserved >= 0),
  low_stock_threshold INT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_inventory_product_warehouse UNIQUE (product_id, warehouse_id)
);

CREATE TABLE sales_order (
  id BIGSERIAL PRIMARY KEY,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE order_line (
  id BIGSERIAL PRIMARY KEY,
  sales_order_id BIGINT NOT NULL REFERENCES sales_order (id),
  product_id BIGINT NOT NULL REFERENCES product (id),
  qty INT NOT NULL CHECK (qty > 0)
);

CREATE TABLE allocation (
  id BIGSERIAL PRIMARY KEY,
  order_line_id BIGINT NOT NULL REFERENCES order_line (id),
  warehouse_id BIGINT NOT NULL REFERENCES warehouse (id),
  qty INT NOT NULL CHECK (qty > 0),
  CONSTRAINT uq_allocation_line_warehouse UNIQUE (order_line_id, warehouse_id)
);

CREATE TABLE reservation (
  id BIGSERIAL PRIMARY KEY,
  allocation_id BIGINT NOT NULL UNIQUE REFERENCES allocation (id),
  qty INT NOT NULL CHECK (qty > 0),
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE stock_movement (
  id BIGSERIAL PRIMARY KEY,
  product_id BIGINT NOT NULL REFERENCES product (id),
  warehouse_id BIGINT NOT NULL REFERENCES warehouse (id),
  type VARCHAR(16) NOT NULL,
  qty INT NOT NULL CHECK (qty > 0),
  ref_type VARCHAR(32) NOT NULL,
  ref_id BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_movement_product_warehouse ON stock_movement (product_id, warehouse_id);
