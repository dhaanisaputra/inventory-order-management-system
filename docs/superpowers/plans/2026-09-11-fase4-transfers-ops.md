# Fase 4 (Transfers + Ops Demo) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stock transfers between warehouses with double-entry movement under one ref id, plus a full-lifecycle demo and honest README (implemented features checked off).

**Architecture:** New `transfer/` module mirrors purchase/returns (record DTOs, `@Transactional` service returning DTOs, controller envelope). Both inventory rows are locked in ascending warehouse-id order (global order, deadlock-free); source deducts, dest adds (created if missing, like purchase receive); two movements (`OUT` source + `IN` dest) share one `stock_transfer` id; source triggers the low-stock notifier; cache evicted.

**Tech Stack:** Java 21, Spring Boot 4.1.1, PostgreSQL 16, Flyway, Testcontainers, JUnit 5 + Mockito + AssertJ. No broker needed (transfer emits only outbox movement events via existing `MovementWriter`).

## Global Constraints

- Branch `phase-four` (from `main`); base package `com.sample.inventory`; API prefix `/api/v1`.
- JPA `ddl-auto: validate` — schema only from Flyway (V1–V4 exist; V5 in Task 1).
- Build JDK 21 (`$env:JAVA_HOME='C:\Program Files\Java\jdk-21'`); `mvn clean` on toolchain switch.
- DTOs as records; entity `@Getter` + `@NoArgsConstructor(PROTECTED)` only, no `@Data`; `equals/hashCode` business key (transfer rows are immutable facts — id equality is fine, no override needed).
- Service return DTO, controller return `ResponseEntity<ApiResponse<…>>`; entity never leaks.
- `@Transactional` services (`readOnly` default); `parallelStream` banned; no H2.
- Boot 4.1.1 test imports; Testcontainers 2.x; `@Import(TestcontainersConfiguration.class)` for ITs.
- `page` 0-based; size default 20 max 100. Sort whitelist + default per controller.
- Conventional Commits; `spotless:check` green; do NOT push.

---

## File Structure

```
src/main/resources/db/migration/V5__stock_transfer.sql
src/main/java/com/sample/inventory/
  inventory/Inventory.java                             # + deduct() (modify, Task 2)
  transfer/StockTransfer.java, TransferRepository.java,
    TransferRequest.java, TransferResponse.java, TransferMapper.java,
    TransferService.java, TransferController.java      # create (Task 2)
src/test/.../transfer/TransferIT.java                  # create (Task 2)
README.md                                              # check off implemented features (modify, Task 3)
```

### Task 1: V5 schema

**Files:**
- Create: `src/main/resources/db/migration/V5__stock_transfer.sql`

**Interfaces:**
- Consumes: V1 tables
- Produces: `stock_transfer` table for Task 2

- [ ] **Step 1: Write `V5__stock_transfer.sql`** (exact, never edit after merge)

```sql
CREATE TABLE stock_transfer (
  id BIGSERIAL PRIMARY KEY,
  product_id BIGINT NOT NULL REFERENCES product (id),
  from_warehouse_id BIGINT NOT NULL REFERENCES warehouse (id),
  to_warehouse_id BIGINT NOT NULL REFERENCES warehouse (id),
  qty INT NOT NULL CHECK (qty > 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

- [ ] **Step 2: Verify migration applies on the dev DB**

Read `src/main/resources/application.yml` for the configured DB (do NOT change or wipe). Run: `./mvnw -q -DskipTests spring-boot:run` (JDK 21), wait for Started + Flyway `Migrating schema "public" to version "5"` in log, stop with Ctrl+C. Record the log line.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V5__stock_transfer.sql
git commit -m "feat: add V5 stock transfer schema"
```

### Task 2: Transfer module

**Files:**
- Modify: `inventory/Inventory.java` (+ `deduct`)
- Create: `transfer/StockTransfer.java`, `transfer/TransferRepository.java`, `transfer/TransferRequest.java`, `transfer/TransferResponse.java`, `transfer/TransferMapper.java`, `transfer/TransferService.java`, `transfer/TransferController.java`
- Create test: `transfer/TransferIT.java` (exact below)

**Interfaces:**
- Consumes: `InventoryRepository.lockOne`, `MovementWriter.write` (MANDATORY), `LowStockNotifier.notifyIfLow`, `ProductRepository`, `WarehouseRepository`
- Produces: `TransferService.create`, `POST/GET /api/v1/transfers`

- [ ] **Step 1: Write IT first (exact), run, expect RED**

`src/test/java/com/sample/inventory/transfer/TransferIT.java`:

```java
package com.sample.inventory.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sample.inventory.TestcontainersConfiguration;
import com.sample.inventory.common.error.DomainException;
import com.sample.inventory.common.error.ErrorCode;
import com.sample.inventory.common.error.InsufficientStockException;
import com.sample.inventory.inventory.Inventory;
import com.sample.inventory.inventory.InventoryRepository;
import com.sample.inventory.movement.MovementType;
import com.sample.inventory.movement.StockMovementRepository;
import com.sample.inventory.product.Product;
import com.sample.inventory.product.ProductRepository;
import com.sample.inventory.warehouse.Warehouse;
import com.sample.inventory.warehouse.WarehouseRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TransferIT {

  @Autowired TransferService transfers;
  @Autowired TransferRepository transferRepo;
  @Autowired InventoryRepository invRepo;
  @Autowired ProductRepository productRepo;
  @Autowired WarehouseRepository warehouseRepo;
  @Autowired StockMovementRepository movementRepo;

  Product p;
  Warehouse src;
  Warehouse dst;

  @BeforeEach
  void seed() {
    movementRepo.deleteAll();
    transferRepo.deleteAll();
    invRepo.deleteAll();
    p = productRepo.save(new Product("SKU-" + System.nanoTime(), "Keyboard"));
    src = warehouseRepo.save(new Warehouse("S-" + System.nanoTime(), "Src", 10));
    dst = warehouseRepo.save(new Warehouse("D-" + System.nanoTime(), "Dst", 20));
    invRepo.save(new Inventory(p, src, 10, 0, 2));
  }

  @Test
  void transferMovesStockWithDoubleEntry() {
    var res = transfers.create(new TransferRequest(p.getId(), src.getId(), dst.getId(), 4));
    assertThat(res.qty()).isEqualTo(4);
    assertThat(res.fromWarehouseCode()).isEqualTo(src.getCode());
    assertThat(res.toWarehouseCode()).isEqualTo(dst.getCode());
    var rows = invRepo.findByProduct_Id(p.getId());
    assertThat(rows).extracting(i -> i.getWarehouse().getCode() + "=" + i.getAvailable()))
        .containsExactlyInAnyOrder(src.getCode() + "=6", dst.getCode() + "=4");
    var moves = movementRepo.findAll();
    assertThat(moves).extracting(m -> m.getType())
        .containsExactlyInAnyOrder(MovementType.OUT, MovementType.IN);
    assertThat(moves).extracting(m -> m.getRefId()).containsOnly(res.id());
    assertThat(moves).extracting(m -> m.getRefType()).containsOnly("TRANSFER");
  }

  @Test
  void transferToNewWarehouseCreatesRow() {
    var res = transfers.create(new TransferRequest(p.getId(), src.getId(), dst.getId(), 10));
    assertThat(res.qty()).isEqualTo(10);
    var inv = invRepo.findByProduct_Id(p.getId());
    assertThat(inv).extracting(i -> i.getAvailable()).containsExactlyInAnyOrder(0, 10);
  }

  @Test
  void insufficientSourceIsRejected() {
    assertThatThrownBy(() -> transfers.create(
        new TransferRequest(p.getId(), src.getId(), dst.getId(), 99)))
        .isInstanceOf(InsufficientStockException.class);
    assertThat(transferRepo.count()).isZero();
    assertThat(movementRepo.count()).isZero();
    assertThat(invRepo.findByProduct_Id(p.getId()).get(0).getAvailable()).isEqualTo(10);
  }

  @Test
  void selfTransferIsRejected() {
    assertThatThrownBy(() -> transfers.create(
        new TransferRequest(p.getId(), src.getId(), src.getId(), 1)))
        .isInstanceOf(DomainException.class)
        .satisfies(e -> assertThat(((DomainException) e).getCode())
            .isEqualTo(ErrorCode.VALIDATION));
  }
}
```

Note: `findByProduct_Id` (plain select, no tx needed) + `TransferRepository` needs no custom methods. `DomainException.getCode()` exists (Fase 1a). Delete order respects FKs (movement → transfer → inventory; product/warehouse accumulate with unique nanoTime codes).

Run: `./mvnw -q -Dtest=TransferIT test`
Expected RED: compilation error (classes do not exist).

- [ ] **Step 2: Implement (exact)**

`Inventory.java` addition (place after `release`, before `add` — read file first to anchor):
```java
  public void deduct(int qty) {
    if (qty <= 0 || qty > available) {
      throw new IllegalArgumentException("cannot deduct " + qty + ", available=" + available);
    }
    available -= qty;
  }
```

`transfer/StockTransfer.java`:
```java
package com.sample.inventory.transfer;

import com.sample.inventory.product.Product;
import com.sample.inventory.warehouse.Warehouse;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "stock_transfer")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockTransfer {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "product_id", nullable = false)
  private Product product;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "from_warehouse_id", nullable = false)
  private Warehouse fromWarehouse;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "to_warehouse_id", nullable = false)
  private Warehouse toWarehouse;

  @Column(nullable = false)
  private int qty;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public StockTransfer(Product product, Warehouse fromWarehouse, Warehouse toWarehouse, int qty) {
    this.product = product;
    this.fromWarehouse = fromWarehouse;
    this.toWarehouse = toWarehouse;
    this.qty = qty;
  }
}
```

`transfer/TransferRepository.java`:
```java
package com.sample.inventory.transfer;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferRepository extends JpaRepository<StockTransfer, Long> {
}
```

`transfer/TransferRequest.java`:
```java
package com.sample.inventory.transfer;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record TransferRequest(@NotNull Long productId, @NotNull Long fromWarehouseId,
    @NotNull Long toWarehouseId, @Positive int qty) {}
```

`transfer/TransferResponse.java`:
```java
package com.sample.inventory.transfer;

import java.time.Instant;

public record TransferResponse(Long id, Long productId, String productSku,
    Long fromWarehouseId, String fromWarehouseCode, Long toWarehouseId,
    String toWarehouseCode, int qty, Instant createdAt) {}
```

`transfer/TransferMapper.java`:
```java
package com.sample.inventory.transfer;

public final class TransferMapper {

  private TransferMapper() {}

  public static TransferResponse toResponse(StockTransfer t) {
    return new TransferResponse(t.getId(), t.getProduct().getId(), t.getProduct().getSku(),
        t.getFromWarehouse().getId(), t.getFromWarehouse().getCode(),
        t.getToWarehouse().getId(), t.getToWarehouse().getCode(), t.getQty(), t.getCreatedAt());
  }
}
```

`transfer/TransferService.java`:
```java
package com.sample.inventory.transfer;

import com.sample.inventory.common.error.DomainException;
import com.sample.inventory.common.error.ErrorCode;
import com.sample.inventory.common.error.InsufficientStockException;
import com.sample.inventory.common.error.NotFoundException;
import com.sample.inventory.events.LowStockNotifier;
import com.sample.inventory.inventory.Inventory;
import com.sample.inventory.inventory.InventoryRepository;
import com.sample.inventory.movement.MovementType;
import com.sample.inventory.movement.MovementWriter;
import com.sample.inventory.product.Product;
import com.sample.inventory.product.ProductRepository;
import com.sample.inventory.warehouse.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TransferService {

  private final TransferRepository transferRepo;
  private final InventoryRepository invRepo;
  private final ProductRepository productRepo;
  private final WarehouseRepository warehouseRepo;
  private final MovementWriter movements;
  private final LowStockNotifier notifier;

  @Transactional
  public TransferResponse create(TransferRequest req) {
    if (req.fromWarehouseId().equals(req.toWarehouseId())) {
      throw new DomainException(ErrorCode.VALIDATION);
    }
    Product product = productRepo.findById(req.productId())
        .orElseThrow(() -> new NotFoundException("product", req.productId()));
    long firstId = Math.min(req.fromWarehouseId(), req.toWarehouseId());
    long secondId = Math.max(req.fromWarehouseId(), req.toWarehouseId());
    var first = invRepo.lockOne(req.productId(), firstId);
    var second = invRepo.lockOne(req.productId(), secondId);
    boolean fromIsFirst = req.fromWarehouseId() == firstId;
    var sourceOpt = fromIsFirst ? first : second;
    var destOpt = fromIsFirst ? second : first;
    var source = sourceOpt.orElseThrow(() -> new InsufficientStockException(product.getSku()));
    if (source.getAvailable() < req.qty()) {
      throw new InsufficientStockException(product.getSku());
    }
    Inventory dest = destOpt.orElseGet(() -> {
      var wh = warehouseRepo.findById(fromIsFirst ? secondId : firstId).orElseThrow(
          () -> new NotFoundException("warehouse", fromIsFirst ? secondId : firstId));
      return invRepo.save(new Inventory(product, wh, 0, 0, 0));
    });
    source.deduct(req.qty());
    dest.add(req.qty());
    var transfer = transferRepo.saveAndFlush(
        new StockTransfer(product, source.getWarehouse(), dest.getWarehouse(), req.qty()));
    movements.write(product, source.getWarehouse(), MovementType.OUT,
        req.qty(), "TRANSFER", transfer.getId());
    movements.write(product, dest.getWarehouse(), MovementType.IN,
        req.qty(), "TRANSFER", transfer.getId());
    notifier.notifyIfLow(source);
    return TransferMapper.toResponse(transfer);
  }

  public TransferResponse get(long id) {
    return transferRepo.findById(id).map(TransferMapper::toResponse)
        .orElseThrow(() -> new NotFoundException("transfer", id));
  }

  public Page<TransferResponse> list(Pageable pageable) {
    return transferRepo.findAll(pageable).map(TransferMapper::toResponse);
  }
}
```
Notes: locks acquired ascending by warehouse id regardless of direction (global order, deadlock-free). `dest.add` reuses the existing domain method. New dest rows start threshold 0 (same convention as purchase receive). `notifyIfLow(source)` — transfer-out is a stock-reducing flow. `TransferMapper.toResponse(transfer)` runs inside tx (lazy proxies initialized).

`transfer/TransferController.java`:
```java
package com.sample.inventory.transfer;

import com.sample.inventory.common.web.ApiResponse;
import com.sample.inventory.common.web.PagedResult;
import com.sample.inventory.common.web.SortValidator;
import jakarta.validation.Valid;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transfers")
@RequiredArgsConstructor
public class TransferController {

  private static final Set<String> SORTABLE = Set.of("createdAt");
  private static final Sort DEFAULT_SORT = Sort.by("createdAt").descending();

  private final TransferService service;

  @PostMapping
  public ResponseEntity<ApiResponse<TransferResponse>> create(
      @Valid @RequestBody TransferRequest req) {
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.create(req)));
  }

  @GetMapping
  public ResponseEntity<ApiResponse<PagedResult<TransferResponse>>> list(Pageable pageable) {
    var page = service.list(SortValidator.validated(pageable, SORTABLE, DEFAULT_SORT));
    return ResponseEntity.ok(ApiResponse.ok(PagedResult.from(page)));
  }

  @GetMapping("/{id}")
  public ResponseEntity<ApiResponse<TransferResponse>> get(@PathVariable long id) {
    return ResponseEntity.ok(ApiResponse.ok(service.get(id)));
  }
}
```

`InventoryService` eviction: `getByProduct` is `@Cacheable("inv")` — transfer mutates stock, so add `@CacheEvict(value = "inv", allEntries = true)` (+ import if missing) on `TransferService.create`. (Same convention as order/purchase/returns/expiry.)

- [ ] **Step 3: GREEN**

Run: `./mvnw -q -Dtest=TransferIT test` (JDK 21)
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/sample/inventory/transfer src/main/java/com/sample/inventory/inventory/Inventory.java src/test/java/com/sample/inventory/transfer
git commit -m "feat: add warehouse transfers with double-entry movement"
```

### Task 3: Full lifecycle demo + README + verify

**Files:**
- Modify: `README.md` (check off implemented Key Features, transfer rows)

**Interfaces:**
- Consumes: everything (final gate Fase 4)
- Produces: green `verify`, end-to-end demo proof, honest README

- [ ] **Step 1: Full verify + format**

Run: `./mvnw spotless:apply` then `./mvnw verify` (JDK 21)
Expected: BUILD SUCCESS — surefire + failsafe green, `spotless:check` clean. Non-format changes from apply → NEEDS_CONTEXT.

- [ ] **Step 2: Full lifecycle demo (dev profile, dev DB only, kafka+redis up)**

1. Read `application.yml`; ensure configured DB reachable (do NOT wipe/reconfigure). `docker compose up -d kafka redis` if not running.
2. Start dev app, wait Started, no Kafka errors.
3. Resolve ids (`GET /products?q=KB-100`, `GET /warehouses`; POST-create if fresh volume).
4. Lifecycle (record every request/response):
   a. Purchase 20 units → receive full → COMPLETED.
   b. Order 6 → confirm → CONFIRMED.
   c. Transfer 4 from warehouse A to B → 201; verify both balances via `GET /products/<id>/inventory`.
   d. Return 2 against the order allocation → 201.
   e. `GET /stock-movements` → IN, RESERVE, OUT, TRANSFER OUT/IN (same refId pair), IN (return).
   f. `LOW STOCK alert` line if threshold trips (set via jshell upsert as in Fase 3 demo if needed; optional — record either way).
5. Stop app.

- [ ] **Step 3: README + commits**

Update the Key Features checklist: check off product/warehouses/real-time inventory/concurrency/order/reservation/returns/purchase/low-stock alerts (event)/sync (transfer)/movement history. Leave unchecked only what is truly absent. Append under `## API` (read README first, no duplication):

```markdown
| POST | /api/v1/transfers | move stock A→B (201), short source → 409 |
| GET | /api/v1/transfers | paged, sort: createdAt |
```

```bash
git add README.md
git commit -m "docs: mark fase 4 complete with transfer api"
```
(Only if spotless reformatted other files: second commit `style: apply spotless leftovers from fase 4` AFTER docs — inspect first; non-format → NEEDS_CONTEXT.)

## Plan Complete — Fase 5 Preview

Next plan (separate file): AI features — demand forecasting + smart replenishment heuristics, anomaly detection on movements, product recommendations, AI assistant stub. All rule-based first (no model dependency), behind `/api/v1/insights/*`.
