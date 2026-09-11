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

- **Kafka:** topics `inventory.order.created/confirmed/cancelled`, `inventory.stock.low`, `inventory.stock.movement`;
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
- **Out of scope (deferred):** auth/security (+ `created_by`/`updated_by` ikut auth),
  multi-currency/pricing, soft delete (tidak dipakai — lihat §9), frontend,
  AI model training (Fase 5 hanya stub + heuristik).

## 6. Katalog endpoint (prefix `/api/v1`)

| Method & path | Sukses | Keterangan |
|---|---|---|
| `POST /products` | 201 | body: `sku` (not blank, max 64), `name` |
| `GET /products` | 200 paged | param `q` (sku/nama) |
| `GET /products/{id}` | 200 / 404 | |
| `PATCH /products/{id}` | 200 | `name`, `active` (nonaktif, bukan hapus) |
| `POST /warehouses` | 201 | `code` unique, `name`, `priority` |
| `GET /warehouses` | 200 list polos | master kecil, tanpa paginasi |
| `GET /inventories` | 200 paged | filter `productId`, `warehouseId`, `lowStockOnly` |
| `POST /orders` | 201 | header opsional `Idempotency-Key` (lihat §14), maks 100 lines |
| `GET /orders` | 200 paged | filter `status`, `from`/`to` |
| `GET /orders/{id}` | 200 / 404 | beserta lines + allocations |
| `POST /orders/{id}/confirm` | 200 idempotent | repeat confirm → current state |
| `POST /orders/{id}/cancel` | 200 | hanya dari `ACTIVE`, selain itu `INVALID_TRANSITION` |
| `POST /returns` | 201 | body `allocation_id + qty` |
| `GET /returns` | 200 paged | |
| `POST /purchase-orders` | 201 | |
| `POST /purchase-orders/{id}/receive` | 200 | parsial boleh; over-receive → 400 |
| `POST /warehouse-transfers` | 201 | body `fromWarehouseId, toWarehouseId, productId, qty` |
| `GET /stock-movements` | 200 paged | filter `productId`, `warehouseId`, `type`, `from`/`to`; tanpa POST (internal only) |

## 7. Katalog error (`ErrorCode`: code + HTTP + message template)

- `VALIDATION` (400), `NOT_FOUND` (404)
- `INSUFFICIENT_STOCK` (409, "stock insufficient for product {sku}")
- `STOCK_CONTENTION` (409, "stock contention, please retry")
- `INVALID_TRANSITION` (409, "order {id} cannot transition from {status}")
- `RETURN_EXCEEDED` (400), `OVER_RECEIVE` (400)
- `IDEMPOTENCY_KEY_CONFLICT` (422, key sama dipakai payload berbeda)
- Bentuk response: sukses `{ "data": T, "error": null }`,
  error `{ "data": null, "error": { "code", "message" } }`.
  Satu `@RestControllerAdvice` memetakan exception → `ErrorCode`; controller
  tidak hardcode status selain via `ResponseEntity` + exception.

## 8. Standar tabel (request/response reusable)

- Response: `ApiResponse<PagedResult<T>>` dengan
  `record PagedResult<T>(List<T> content, int page, int size, long totalElements, int totalPages)`
  + factory `from(Page<T>)`. `page` 0-based (konvensi Spring), dicatat di Swagger.
- Request list: `q` (search didefinisikan per endpoint) + `from`/`to` (`Instant`,
  validasi `from <= to`) + Pageable (`size` default 20, max 100).
- Filter spesifik per endpoint sebagai `record` eksplisit via `@ParameterObject`
  (mis. `MovementFilter(productId, warehouseId, types)`). Larangan: filter generik
  `Map<String,String>` (hilang type-safety + dokumentasi).
- **Sort whitelist:** tiap endpoint deklarasikan kolom sortable (map kolom UI → path entity);
  kolom tak dikenal → fallback sort default. Default: order/movement `createdAt,desc`;
  product `name,asc`; inventory `product.name,asc`.

## 9. Audit policy

- `created_at` di semua tabel (`@CreationTimestamp`); `updated_at` hanya master data
  (`product`, `warehouse`, `inventory`) — skip di tabel append-only (`stock_movement`, `outbox`).
- `created_by`/`updated_by` ditunda sampai ada auth (diisi `"system"` = data mati);
  saat itu aktifkan `@EnableJpaAuditing` + `@CreatedBy`/`@LastModifiedBy` sekaligus.
- Tanpa soft delete: hapus fisik dicegah FK `RESTRICT` bila berelasi; nonaktif via flag `active`.

## 10. Constants policy

- Larangan god-class `AppConstants`. Konstanta tinggal serumah pemiliknya:
  status/type sebagai enum (`OrderStatus`, `ReservationStatus`, `MovementType`);
  nama topik di final class `KafkaTopics`; cache key di `CacheKeys`.
- Error codes sebagai enum `ErrorCode` (code + HTTP status + message template)
  — pengganti konstanta string sekaligus katalog error hidup di kode.
- Nilai config (TTL, interval, batas) via `@ConfigurationProperties`, bukan konstanta.

## 11. Test strategy

- Unit murni Mockito (service + mapper). Tanpa H2 — dialek ≠ Postgres
  (`SKIP LOCKED`, perilaku lock beda). Slice repository + concurrency wajib
  Testcontainers Postgres.
- `java.time.Clock` sebagai bean; logic pakai `Instant.now(clock)` agar expiry testable.
- Wajib: concurrency (2+ thread rebutan unit terakhir, tepat 1 menang + balance check),
  expiry TTL pendek, double confirm, split sesuai prioritas.
- Nama test `shouldX_whenY`, struktur Given-When-Then, satu behavior per test.

## 12. Seed & dev resilience

- Seed demo hanya di profile `dev`/`demo` (1 produk + 2 warehouse + stok), bukan `data.sql`
  global agar tidak mengotori test.
- Fase 1 harus boot & lolos test dengan hanya Postgres yang up; bean Kafka/Redis
  conditional/lazy agar dev tak tergantung semua service.

## 13. Outbox & topik (persiapan Fase 3, difreeze sekarang)

- Tabel `outbox` masuk migrasi Fase 3 (YAGNI di V1): `id`, `topic`, `key`,
  `payload` JSONB, `created_at`, `published_at` nullable; relay `@Scheduled` kirim ke Kafka.
- Nama topik: `inventory.order.created`, `inventory.order.confirmed`,
  `inventory.order.cancelled`, `inventory.stock.low`, `inventory.stock.movement`;
  key = product id.

## 14. Limit validasi & idempotency

- Maks 100 lines per order; `qty` `@Positive`; `sku`/`code` not blank max 64;
  `size` ≤ 100; `from <= to`.
- `POST /orders` terima header `Idempotency-Key` (opsional): key + payload sama →
  kembalikan order asli; key sama + payload beda → `422 IDEMPOTENCY_KEY_CONFLICT`.

## 15. Definition of Done per fase

Build hijau (termasuk Spotless check), test slice lolos, demo curl end-to-end jalan,
README + tabel endpoint update, deviasi dari spec ditulis balik ke spec file ini.

## Appendix A — Coding Conventions

- DRY tanpa generic base class (`AbstractCrudService` dilarang); reuse via mapper,
  repository method bernama, dan handler global.
- Anti N+1: read pakai `JOIN FETCH`/`@EntityGraph`, list pakai DTO projection;
  write path lock entity sebagaimana mestinya.
- Stream untuk transformasi deklaratif (`map`/`filter`/`collect`); `parallelStream`
  dilarang di request path (JPA tidak thread-safe). Hot path kecil boleh loop biasa.
- Service return DTO/domain (entity tidak bocor; DTO sebagai `record`);
  controller return `ResponseEntity`. Tanpa MapStruct di Fase 1 (mapper static hand-written).
- OOP komposisi; service kecil per agregat; domain exception sebagai bahasa bisnis.
- Guard clause/early return, nesting ≤ 2, maks 3 param, tanpa flag boolean,
  tanpa magic (enum/konstanta pemilik), komentar hanya untuk *why*.
- `Optional` hanya return type; koleksi tak pernah null; `Instant` untuk waktu;
  `BigDecimal` untuk uang.
- Lombok: tanpa `@Data`/`@EqualsAndHashCode` di entity; pakai `@Getter` +
  `@NoArgsConstructor(PROTECTED)`; `equals/hashCode` pakai business key.
- Logging SLF4J parameterized, level tepat, sekali di boundary, tanpa data sensitif,
  tanpa `System.out`.
- Constructor injection + field `final`; `@Transactional` di service
  (`readOnly` untuk read).
