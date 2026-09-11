# Inventory & Order Management System — Design Spec

- Date: 2026-09-11
- Stack: Java 21, Spring Boot 4.1.1, PostgreSQL 16, Redis 7, Kafka (KRaft), Docker
- Base package: `com.sample.inventory`
- DB: `inventory_management` (local, user `postgres`), JPA `ddl-auto: validate` + Flyway migrations
- Status: approved per section by owner, ready for implementation plan

## Agreed decisions

1. Satu order line boleh dipecah ke beberapa warehouse (split allocation).
2. Stok dipegang via reservation + expiry (TTL), lalu confirm / cancel / expired.
3. Urutan alokasi mengikuti `warehouse.priority` (kecil = diutamakan).
4. Barang retur kembali ke warehouse asal alokasi (return line refer ke `allocation`).
5. Locking: pessimistic `FOR UPDATE` dalam satu transaksi, rows selalu di-lock dalam urutan
   global yang sama (`warehouse.priority`, `warehouse.id`) — deadlock-free by construction.
6. Lock timeout via `@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000")`
   (namespace `jakarta.*` karena Spring Boot 4.x); timeout → `409 STOCK_CONTENTION`.
7. Confirm idempotent: order yang sudah `CONFIRMED` me-return current state, bukan error.
8. Transfer mencatat dua movement (`OUT` sumber + `IN` tujuan) dengan satu `ref_id` yang sama.
9. API docs: springdoc-openapi 3.1.0 (`springdoc-openapi-starter-webmvc-ui`), UI di `/swagger-ui.html`.

## 1. Fase breakdown (11 Key Features → 5 fase)

- **Fase 1 — Core slice:** product, warehouse (`priority`), inventory (`available`, `reserved`),
  order + split allocation by priority, reservation + expiry worker, movement history.
  + Flyway V1, springdoc, concurrency test (2 order rebutan stok terakhir).
- **Fase 2 — Inbound & retur:** purchase order + receiving (movement `IN`), returns ke warehouse asal.
- **Fase 3 — Event & cache:** Kafka (`order.created/confirmed/cancelled`, `stock.low`,
  `stock.movement`), Redis cache read inventory, low-stock alert consumer.
- **Fase 4 — Ops:** transfer antar warehouse, actuator + Docker Compose siap demo.
- **Fase 5 — AI (nice to have):** demand forecasting → smart replenishment, anomaly detection
  di movement, product recommendation, AI assistant (stub endpoint dulu).

## 2. Data model (JPA)

- `product`: `id`, `sku` unique not null, `name`, `active`.
- `warehouse`: `id`, `code` unique, `name`, `priority` (int).
- `inventory`: `id`, `product_id` + `warehouse_id` unique, `available`, `reserved`,
  `low_stock_threshold`. Tanpa `@Version` (pessimistic lock; optimistic = evolusi Fase lanjutan).
- `sales_order` (bukan `order`, reserved keyword Postgres): `id`, `status`
  (`PENDING → CONFIRMED / CANCELLED`), `created_at`.
- `order_line`: `id`, `sales_order_id` (`@ManyToOne`), `product_id`, `qty`.
- `allocation`: `id`, `order_line_id`, `warehouse_id`, `qty`. Unique per (line, warehouse).
- `reservation`: `id`, `allocation_id` unique (1:1), `qty`, `status`
  (`ACTIVE / CONFIRMED / CANCELLED / EXPIRED`), `expires_at`.
- `stock_movement`: append-only, tanpa update. `product_id`, `warehouse_id`, `type`
  (`IN / OUT / RESERVE / RELEASE / TRANSFER`), `qty`, `ref_type + ref_id`, `created_at`.
- Fase 2 reuse model yang sama: `purchase_order` + `purchase_order_line`,
  `return_order` + `return_line` (`allocation_id` FK).
- Constraint: CHECK `available >= 0`, `reserved >= 0`; FK `ON DELETE RESTRICT`.

## 3. Alur order + reservation

`POST /orders` → `createOrder(lines)`, satu `@Transactional` method:

1. Per line: cari kandidat inventory produk itu `WHERE available > 0 ORDER BY priority, warehouse.id`.
2. Lock kandidat `FOR UPDATE` (`PESSIMISTIC_WRITE` + lock timeout 3000ms) dalam urutan yang sama.
3. Alokasi serakah ikut prioritas sampai `qty` terpenuhi; total available < qty → rollback penuh,
   `409 INSUFFICIENT_STOCK` (tanpa partial order).
4. Tulis `sales_order(PENDING)` → `order_line` → `allocation` → `reservation(ACTIVE,
   expires_at = now + TTL)` dengan TTL default 30 menit (configurable via
   `app.reservation-ttl`, format Duration); `available -= q, reserved += q`; movement `RESERVE` per alokasi.

Confirm / cancel / expiry:

- `POST /orders/{id}/confirm`: hanya dari `ACTIVE` (repeat confirm → return current state);
  `reserved -= q`, status `CONFIRMED`, movement `OUT`, order `CONFIRMED`.
- `POST /orders/{id}/cancel` + `@Scheduled` expiry worker (60s, batch 500,
  `FOR UPDATE SKIP LOCKED`): `reserved -= q, available += q`, status `CANCELLED / EXPIRED`,
  movement `RELEASE`.
- Lock timeout → `409 STOCK_CONTENTION` (client retry); Bean Validation di DTO
  (`@Positive` qty, product exists).
- Invariant yang selalu dijaga: `IN - OUT = available + reserved` per (product, warehouse).

## 4. Returns, purchase, alert, transfer

- **Returns** (`POST /returns`, body `allocation_id + qty`): validasi kumulatif retur ≤ qty alokasi;
  lock inventory warehouse asal → `available += q`, movement `IN` (`ref_type=RETURN`).
- **Purchase** (`POST /purchase-orders` → `POST /purchase-orders/{id}/receive`): receive parsial
  boleh sampai total line terpenuhi; over-receive → `400`; movement `IN` (`ref_type=PURCHASE`).
- **Low-stock alert:** setiap mutasi pengurang `available` (confirm, transfer-out) cek
  `available < low_stock_threshold` → publish `stock.low` fire-and-forget (kegagalan publish
  tak menggagalkan transaksi).
- **Transfer** (`POST /warehouses/transfers`): lock dua rows dalam urutan global yang sama
  (`ORDER BY warehouse.id`); sumber `available -=`, tujuan `+=`; movement `TRANSFER` OUT+IN
  satu `ref_id`.

## 5. Events, cache, testing, demo

- **Kafka:** topics `order.created/confirmed/cancelled`, `stock.low`, `stock.movement`;
  key = product id (urutan per produk terjaga). Producer via **transactional outbox**:
  tulis ke tabel `outbox` dalam transaksi yang sama, relay `@Scheduled` kirim ke Kafka.
- **Redis:** read path `GET /inventory` saja, key `inv:{productId}`, TTL 30s, invalidasi
  (`@CacheEvict`) di setiap mutasi. Write path selalu baca DB ter-lock — cache stale
  tak bisa menyebabkan oversell.
- **Testing:** concurrency (2+ thread rebutan unit terakhir, tepat 1 menang + balance check);
  expiry (TTL pendek); idempotency (double confirm); split-priority. H2 untuk unit,
  Testcontainers Postgres untuk slice concurrency.
- **Demo:** `docker compose up` → seed 1 produk + 2 warehouse → curl: create (split) →
  confirm → return → purchase receive → movement balance nol. Health via actuator.
- **Out of scope (deferred):** auth/security, multi-currency/pricing, frontend, AI model
  training (Fase 5 hanya stub + heuristik).
