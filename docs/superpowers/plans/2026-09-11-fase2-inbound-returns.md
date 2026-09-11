# Fase 2 (Inbound, Returns, Hardening) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Inbound (purchase + receiving) dan returns ke warehouse asal, plus hardening order dari temuan final review Fase 1 (exit order kadaluarsa, lock confirm-vs-expiry, global lock order).

**Architecture:** Modul baru `purchase/` dan `returns/` mengikuti pola Fase 1 (record DTO, service `@Transactional` return DTO, controller `ResponseEntity<ApiResponse<…>>`). Hardening: confirm/cancel mengunci reservations (`PESSIMISTIC_WRITE`) dan mengunci inventory dalam urutan global (lines by productId, allocations by warehouseId); expiry membatalkan order yang fully-expired (amandemen spec §3: order tidak lagi strand PENDING).

**Tech Stack:** Java 21, Spring Boot 4.1.1, PostgreSQL 16, Flyway, Testcontainers, JUnit 5 + Mockito + AssertJ.

## Global Constraints

- Branch `phase-two` (from `main`); base package `com.sample.inventory`; API prefix `/api/v1`.
- JPA `ddl-auto: validate` — schema hanya dari Flyway (V1–V2 ada; V3 di Task 1).
- Build JDK 21 (`$env:JAVA_HOME='C:\Program Files\Java\jdk-21'`); `mvn clean` tiap ganti toolchain.
- DTOs as records; entity `@Getter` + `@NoArgsConstructor(PROTECTED)` saja, tanpa `@Data`; `equals/hashCode` business key.
- Service return DTO, controller return `ResponseEntity<ApiResponse<…>>`; entity tidak bocor.
- `@Transactional` di service (`readOnly` default); `parallelStream` dilarang; tanpa H2.
- Boot 4.1.1 imports: `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest`, `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`, `org.springframework.test.context.bean.override.mockito.MockitoBean`.
- Testcontainers 2.x non-generic `org.testcontainers.postgresql.PostgreSQLContainer` (hanya via `@Import(TestcontainersConfiguration.class)`).
- `page` 0-based; size default 20 max 100. Sort whitelist + default per controller.
- Conventional Commits; `spotless:check` hijau; jangan push.

---

## File Structure

```
src/main/resources/db/migration/V3__purchase_returns.sql
src/main/java/com/sample/inventory/
  common/error/OverReceiveException.java               # create (Task 3)
  common/error/ReturnExceededException.java            # create (Task 4)
  order/AllocationRepository.java                      # create (Task 1)
  order/ReservationRepository.java                     # + lockByOrderId, existsActive (modify, Task 2)
  order/OrderService.java                              # confirm/cancel via locked map + sorted locking (modify, Task 2)
  order/ReservationExpiryService.java                  # cancel fully-expired orders (modify, Task 2)
  purchase/PurchaseOrderStatus.java, PurchaseOrder.java, PurchaseOrderLine.java,
    PurchaseOrderRepository.java, CreatePurchaseRequest.java, ReceiveRequest.java,
    PurchaseResponse.java, PurchaseMapper.java, PurchaseService.java, PurchaseController.java
  returns/ReturnOrder.java, ReturnLine.java,
    ReturnOrderRepository.java, ReturnLineRepository.java,
    CreateReturnRequest.java, ReturnResponse.java, ReturnMapper.java,
    ReturnService.java, ReturnController.java
src/test/.../order/AllocationRepositoryIT.java (Task 1, lockByOrderId behavior via service tests instead — see Task 1)
src/test/.../order/ReservationExpiryIT.java            # status CANCELLED expectation (modify, Task 2)
src/test/.../order/OrderConfirmCancelIT.java           # + confirm-after-expiry test (modify, Task 2)
src/test/.../purchase/PurchaseIT.java                  # create + partial/complete/over-receive (Task 3)
src/test/.../returns/ReturnIT.java                     # happy path + exceed + cumulative (Task 4)
```

### Task 1: V3 schema + AllocationRepository

**Files:**
- Create: `src/main/resources/db/migration/V3__purchase_returns.sql`, `order/AllocationRepository.java`

**Interfaces:**
- Consumes: V1 tables
- Produces: `purchase_order`, `purchase_order_line`, `return_order`, `return_line`; `AllocationRepository.findById` for Task 4

- [ ] **Step 1: Write `V3__purchase_returns.sql`** (exact, never edit after merge)

```sql
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
```

`order/AllocationRepository.java`:

```java
package com.sample.inventory.order;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AllocationRepository extends JpaRepository<Allocation, Long> {
}
```

- [ ] **Step 2: Verify migration applies**

Run: `$env:JAVA_HOME='C:\Program Files\Java\jdk-21'; ./mvnw -q -DskipTests spring-boot:run`, wait for Started (Flyway `Migrating schema "public" to version "3"` in log), stop with Ctrl+C. Uses the owner-configured dev DB — do NOT wipe; V3 applies incrementally on top of V1–V2.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V3__purchase_returns.sql src/main/java/com/sample/inventory/order/AllocationRepository.java
git commit -m "feat: add V3 purchase and returns schema"
```

### Task 2: Order hardening (locks, sorted locking, expiry-cancel)

**Files:**
- Modify: `order/ReservationRepository.java` (+2 methods), `order/OrderService.java` (confirm/cancel rewrite), `order/ReservationExpiryService.java` (+cancel fully-expired), `order/ReservationExpiryIT.java` (status expectation), `order/OrderConfirmCancelIT.java` (+1 test)

**Interfaces:**
- Consumes: Task 1 (nothing new needed); existing entities/services
- Produces: race-safe confirm/cancel/expiry semantics for Tasks 3–5 and Fase 3

**Design decisions (locked, amend spec §3 on implementation):** fully-expired PENDING orders transition to CANCELLED by the expiry worker (no stock movement — stock already restored via RELEASE). Confirm/cancel lock reservations first (`lockByOrderId`), then inventories in global order (products asc, warehouses asc).

- [ ] **Step 1: Update the expiry IT expectation + add post-expiry test (RED first)**

In `ReservationExpiryIT.expiredReservationRestoresStock`, change:
```java
    assertThat(orders.get(created.id()).status()).isEqualTo(OrderStatus.PENDING);
```
to:
```java
    assertThat(orders.get(created.id()).status()).isEqualTo(OrderStatus.CANCELLED);
```

In `OrderConfirmCancelIT` (read file first; class is `@Transactional`), append test:
```java
  @Test
  void confirmAfterExpiryIsInvalid() {
    var created = pendingOrder(2);
    expiry.expireBatch(java.time.Instant.now().plusSeconds(3600));
    assertThatThrownBy(() -> orders.confirm(created.id()))
        .isInstanceOf(InvalidTransitionException.class);
  }
```
Hmm — reservations expire at create+TTL(30m default; test ctx uses default yml, no TestPropertySource here). `expireBatch(now+3600s)` forces due. Requires `@Autowired ReservationExpiryService expiry;` field added to the test class. `InvalidTransitionException` import already present in that file (used by confirmAfterCancelIsInvalid). The expired order becomes CANCELLED by the new worker logic → confirm throws InvalidTransition. 

Run: `./mvnw -q -Dtest='ReservationExpiryIT,OrderConfirmCancelIT' test`
Expected RED: expiry test fails (still PENDING), new test fails (confirm succeeds).

- [ ] **Step 2: Implement (exact)**

`ReservationRepository` additions:
```java
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
  @Query("select r from Reservation r where r.allocation.orderLine.order.id = :orderId")
  List<Reservation> lockByOrderId(long orderId);

  boolean existsByAllocationOrderLineOrderIdAndStatus(long orderId, ReservationStatus status);
```
imports: `jakarta.persistence.LockModeType`, `jakarta.persistence.QueryHint`, `org.springframework.data.jpa.repository.Lock`, `org.springframework.data.jpa.repository.QueryHints`, `java.util.List` (List already imported for lockDue — check, add only if missing).

`OrderService.confirm` replacement (exact — replaces whole method):
```java
  @Transactional
  public OrderResponse confirm(long id) {
    var order = orderRepo.findDetailedById(id)
        .orElseThrow(() -> new NotFoundException("order", id));
    if (order.getStatus() == OrderStatus.CONFIRMED) {
      return OrderMapper.toResponse(order);
    }
    order.confirm();
    var resByAlloc = reservationRepo.lockByOrderId(id).stream()
        .collect(java.util.stream.Collectors.toMap(r -> r.getAllocation().getId(), r -> r));
    var sortedLines = order.getLines().stream()
        .sorted(java.util.Comparator.comparing(l -> l.getProduct().getId()))
        .toList();
    for (var line : sortedLines) {
      var sortedAllocs = line.getAllocations().stream()
          .sorted(java.util.Comparator.comparing(a -> a.getWarehouse().getId()))
          .toList();
      for (var alloc : sortedAllocs) {
        var inv = invRepo.lockOne(line.getProduct().getId(), alloc.getWarehouse().getId())
            .orElseThrow(() -> new NotFoundException("inventory",
                line.getProduct().getId() + "/" + alloc.getWarehouse().getId()));
        inv.confirm(alloc.getQty());
        resByAlloc.get(alloc.getId()).confirm();
        movements.write(line.getProduct(), alloc.getWarehouse(), MovementType.OUT,
            alloc.getQty(), "ORDER", order.getId());
      }
    }
    return OrderMapper.toResponse(order);
  }
```
Use proper imports instead of fully-qualified names: add `java.util.Comparator`, `java.util.Map`, `java.util.stream.Collectors`... simpler: build map with explicit loop to avoid Collector import fuss? Brief-exact: use imports `java.util.Comparator` + `java.util.HashMap` + `java.util.Map`:
```java
    Map<Long, Reservation> resByAlloc = new HashMap<>();
    for (var r : reservationRepo.lockByOrderId(id)) {
      resByAlloc.put(r.getAllocation().getId(), r);
    }
```
(Read the current file first and match its import style; keep everything else untouched.)

`OrderService.cancel` — same shape with `inv.release(...)`, `res.cancel()`, `MovementType.RELEASE`, `order.cancel()`.

`ReservationExpiryService.expireBatch` — after the existing loop, append (same tx):
```java
    var orderIds = due.stream()
        .map(r -> r.getAllocation().getOrderLine().getOrder().getId())
        .collect(java.util.stream.Collectors.toSet());
    for (var orderId : orderIds) {
      var order = orderRepo.findById(orderId).orElse(null);
      if (order != null
          && order.getStatus() == OrderStatus.PENDING
          && !reservationRepo.existsByAllocationOrderLineOrderIdAndStatus(
              orderId, ReservationStatus.ACTIVE)) {
        order.cancel();
      }
    }
    return due.size();
```
Needs `SalesOrderRepository orderRepo` field added + imports (`OrderStatus`, `Collectors` or Anthropic... use `java.util.HashSet` loop to stay import-light — implementer: use explicit `new java.util.HashSet<>()`? No: add proper `import java.util.Set; import java.util.HashSet;`. Keep style consistent with file.)

- [ ] **Step 3: GREEN**

Run: `./mvnw -q -Dtest='ReservationExpiryIT,OrderConfirmCancelIT,OrderCreateIT' test` (JDK 21)
Expected: all green (expiry 2 + confirm/cancel 5 + create 3 = 10).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/sample/inventory/order/ReservationRepository.java src/main/java/com/sample/inventory/order/OrderService.java src/main/java/com/sample/inventory/order/ReservationExpiryService.java src/test/java/com/sample/inventory/order/ReservationExpiryIT.java src/test/java/com/sample/inventory/order/OrderConfirmCancelIT.java
git commit -m "fix: harden order transitions with locks and expiry cancel"
```

### Task 3: Purchase module

**Files:**
- Create: `common/error/OverReceiveException.java`
- Create: `purchase/PurchaseOrderStatus.java`, `purchase/PurchaseOrder.java`, `purchase/PurchaseOrderLine.java`, `purchase/PurchaseOrderRepository.java`, `purchase/CreatePurchaseRequest.java`, `purchase/ReceiveRequest.java`, `purchase/PurchaseResponse.java`, `purchase/PurchaseMapper.java`, `purchase/PurchaseService.java`, `purchase/PurchaseController.java`
- Create test: `purchase/PurchaseIT.java`

**Interfaces:**
- Consumes: `InventoryRepository.lockOne`, `MovementWriter`, Task 2 (nothing breaking)
- Produces: `POST/GET /api/v1/purchase-orders`, `POST /purchase-orders/{id}/receive`

- [ ] **Step 1: Write IT first (exact)**

`src/test/java/com/sample/inventory/purchase/PurchaseIT.java`:

```java
package com.sample.inventory.purchase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sample.inventory.TestcontainersConfiguration;
import com.sample.inventory.common.error.OverReceiveException;
import com.sample.inventory.inventory.Inventory;
import com.sample.inventory.inventory.InventoryRepository;
import com.sample.inventory.movement.MovementType;
import com.sample.inventory.movement.StockMovementRepository;
import com.sample.inventory.product.Product;
import com.sample.inventory.product.ProductRepository;
import com.sample.inventory.purchase.CreatePurchaseRequest.CreatePurchaseLine;
import com.sample.inventory.purchase.ReceiveRequest.ReceiveItem;
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
class PurchaseIT {

  @Autowired PurchaseService purchases;
  @Autowired PurchaseOrderRepository poRepo;
  @Autowired InventoryRepository invRepo;
  @Autowired ProductRepository productRepo;
  @Autowired WarehouseRepository warehouseRepo;
  @Autowired StockMovementRepository movementRepo;

  Product p;
  Warehouse w;

  @BeforeEach
  void seed() {
    movementRepo.deleteAll();
    poRepo.deleteAll();
    invRepo.deleteAll();
    p = productRepo.save(new Product("SKU-" + System.nanoTime(), "Keyboard"));
    w = warehouseRepo.save(new Warehouse("W-" + System.nanoTime(), "W", 1));
    invRepo.save(new Inventory(p, w, 0, 0, 5));
  }

  @Test
  void partialThenCompleteReceiving() {
    var po = purchases.create(new CreatePurchaseRequest(
        List.of(new CreatePurchaseLine(p.getId(), 10))));
    assertThat(po.status()).isEqualTo(PurchaseOrderStatus.OPEN);
    var afterFirst = purchases.receive(po.id(),
        new ReceiveRequest(List.of(new ReceiveItem(p.getId(), w.getId(), 4))));
    assertThat(afterFirst.status()).isEqualTo(PurchaseOrderStatus.OPEN);
    var afterSecond = purchases.receive(po.id(),
        new ReceiveRequest(List.of(new ReceiveItem(p.getId(), w.getId(), 6))));
    assertThat(afterSecond.status()).isEqualTo(PurchaseOrderStatus.COMPLETED);
    var inv = invRepo.findByProduct_Id(p.getId()).get(0);
    assertThat(inv.getAvailable()).isEqualTo(10);
    assertThat(movementRepo.findAll()).extracting(m -> m.getType())
        .containsOnly(MovementType.IN);
  }

  @Test
  void overReceiveIsRejected() {
    var po = purchases.create(new CreatePurchaseRequest(
        List.of(new CreatePurchaseLine(p.getId(), 5))));
    assertThatThrownBy(() -> purchases.receive(po.id(),
        new ReceiveRequest(List.of(new ReceiveItem(p.getId(), w.getId(), 6)))))
        .isInstanceOf(OverReceiveException.class);
    var inv = invRepo.findByProduct_Id(p.getId()).get(0);
    assertThat(inv.getAvailable()).isZero();
  }

  @Test
  void unknownProductLineIsRejected() {
    var po = purchases.create(new CreatePurchaseRequest(
        List.of(new CreatePurchaseLine(p.getId(), 5))));
    assertThatThrownBy(() -> purchases.receive(po.id(),
        new ReceiveRequest(List.of(new ReceiveItem(999999L, w.getId(), 1)))))
        .isInstanceOf(com.sample.inventory.common.error.NotFoundException.class);
  }
}
```

Note: `invRepo.findByProduct_Id` (added in Fase 1b Task 5) is a plain select — no tx needed in non-transactional test. `poRepo.deleteAll()` cascades lines (CascadeType.ALL + orphanRemoval — include on entity like SalesOrder).

Run: `./mvnw -q -Dtest=PurchaseIT test` → expect FAIL (compilation error).

- [ ] **Step 2: Implement (exact)**

`common/error/OverReceiveException.java`:
```java
package com.sample.inventory.common.error;

public class OverReceiveException extends DomainException {

  public OverReceiveException(long lineId) {
    super(ErrorCode.OVER_RECEIVE, lineId);
  }
}
```
(`ErrorCode.OVER_RECEIVE` exists since Fase 1b Task 3 — verify, do not duplicate.)

`purchase/PurchaseOrderStatus.java`:
```java
package com.sample.inventory.purchase;

public enum PurchaseOrderStatus {
  OPEN,
  COMPLETED
}
```

`purchase/PurchaseOrder.java`:
```java
package com.sample.inventory.purchase;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "purchase_order")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PurchaseOrder {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private PurchaseOrderStatus status = PurchaseOrderStatus.OPEN;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<PurchaseOrderLine> lines = new ArrayList<>();

  public void addLine(PurchaseOrderLine line) {
    lines.add(line);
  }

  public void completeIfFulfilled() {
    boolean all = lines.stream().allMatch(l -> l.getReceivedQty() >= l.getOrderedQty());
    if (all) {
      status = PurchaseOrderStatus.COMPLETED;
    }
  }
}
```

`purchase/PurchaseOrderLine.java`:
```java
package com.sample.inventory.purchase;

import com.sample.inventory.common.error.OverReceiveException;
import com.sample.inventory.product.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "purchase_order_line")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PurchaseOrderLine {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "purchase_order_id", nullable = false)
  private PurchaseOrder order;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "product_id", nullable = false)
  private Product product;

  @Column(name = "ordered_qty", nullable = false)
  private int orderedQty;

  @Column(name = "received_qty", nullable = false)
  private int receivedQty;

  public PurchaseOrderLine(PurchaseOrder order, Product product, int orderedQty) {
    this.order = order;
    this.product = product;
    this.orderedQty = orderedQty;
  }

  public int remaining() {
    return orderedQty - receivedQty;
  }

  public void receive(int qty) {
    if (qty <= 0 || receivedQty + qty > orderedQty) {
      throw new OverReceiveException(id);
    }
    receivedQty += qty;
  }
}
```
Note: `OverReceiveException(id)` with null id pre-persist — only called on managed lines (id present). Fine.

`purchase/PurchaseOrderRepository.java`:
```java
package com.sample.inventory.purchase;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {
}
```
(List endpoint: `findAll(pageable)` default — no custom search; status filter YAGNI for Fase 2.)

`purchase/CreatePurchaseRequest.java`:
```java
package com.sample.inventory.purchase;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreatePurchaseRequest(
    @Size(min = 1, max = 100) List<@Valid CreatePurchaseLine> lines) {

  public record CreatePurchaseLine(@NotNull Long productId, @Positive int orderedQty) {}
}
```

`purchase/ReceiveRequest.java`:
```java
package com.sample.inventory.purchase;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ReceiveRequest(
    @Size(min = 1, max = 100) List<@Valid ReceiveItem> items) {

  public record ReceiveItem(@NotNull Long productId, @NotNull Long warehouseId, @Positive int qty) {}
}
```

`purchase/PurchaseResponse.java`:
```java
package com.sample.inventory.purchase;

import java.time.Instant;
import java.util.List;

public record PurchaseResponse(Long id, PurchaseOrderStatus status,
    List<PurchaseLineDto> lines, Instant createdAt) {

  public record PurchaseLineDto(Long id, Long productId, String productSku,
      int orderedQty, int receivedQty) {}
}
```

`purchase/PurchaseMapper.java`:
```java
package com.sample.inventory.purchase;

public final class PurchaseMapper {

  private PurchaseMapper() {}

  public static PurchaseResponse toResponse(PurchaseOrder o) {
    return new PurchaseResponse(o.getId(), o.getStatus(),
        o.getLines().stream().map(l -> new PurchaseResponse.PurchaseLineDto(l.getId(),
            l.getProduct().getId(), l.getProduct().getSku(), l.getOrderedQty(), l.getReceivedQty()))
            .toList(),
        o.getCreatedAt());
  }
}
```

`purchase/PurchaseService.java`:
```java
package com.sample.inventory.purchase;

import com.sample.inventory.common.error.DomainException;
import com.sample.inventory.common.error.ErrorCode;
import com.sample.inventory.common.error.NotFoundException;
import com.sample.inventory.inventory.Inventory;
import com.sample.inventory.inventory.InventoryRepository;
import com.sample.inventory.movement.MovementType;
import com.sample.inventory.movement.MovementWriter;
import com.sample.inventory.product.Product;
import com.sample.inventory.product.ProductRepository;
import com.sample.inventory.warehouse.WarehouseRepository;
import java.util.Comparator;
import java.util.HashSet;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PurchaseService {

  private final PurchaseOrderRepository poRepo;
  private final ProductRepository productRepo;
  private final InventoryRepository invRepo;
  private final WarehouseRepository warehouseRepo;
  private final MovementWriter movements;

  @Transactional
  public PurchaseResponse create(CreatePurchaseRequest req) {
    var seen = new HashSet<Long>();
    for (var l : req.lines()) {
      if (!seen.add(l.productId())) {
        throw new DomainException(ErrorCode.VALIDATION);
      }
    }
    var po = new PurchaseOrder();
    for (var l : req.lines()) {
      Product product = productRepo.findById(l.productId())
          .orElseThrow(() -> new NotFoundException("product", l.productId()));
      po.addLine(new PurchaseOrderLine(po, product, l.orderedQty()));
    }
    return PurchaseMapper.toResponse(poRepo.save(po));
  }

  public PurchaseResponse get(long id) {
    return poRepo.findById(id).map(PurchaseMapper::toResponse)
        .orElseThrow(() -> new NotFoundException("purchase order", id));
  }

  public Page<PurchaseResponse> list(Pageable pageable) {
    return poRepo.findAll(pageable).map(PurchaseMapper::toResponse);
  }

  @Transactional
  public PurchaseResponse receive(long id, ReceiveRequest req) {
    var po = poRepo.findById(id)
        .orElseThrow(() -> new NotFoundException("purchase order", id));
    if (po.getStatus() != PurchaseOrderStatus.OPEN) {
      throw new DomainException(ErrorCode.VALIDATION);
    }
    var sorted = req.items().stream()
        .sorted(Comparator.comparing(ReceiveRequest.ReceiveItem::productId)
            .thenComparing(ReceiveRequest.ReceiveItem::warehouseId))
        .toList();
    for (var item : sorted) {
      var line = po.getLines().stream()
          .filter(l -> l.getProduct().getId().equals(item.productId()))
          .findFirst()
          .orElseThrow(() -> new NotFoundException("purchase line", item.productId()));
      var inv = invRepo.lockOne(item.productId(), item.warehouseId())
          .orElseGet(() -> {
            var wh = warehouseRepo.findById(item.warehouseId())
                .orElseThrow(() -> new NotFoundException("warehouse", item.warehouseId()));
            return invRepo.save(new Inventory(line.getProduct(), wh, 0, 0, 0));
          });
      line.receive(item.qty());
      inv.add(item.qty());
      movements.write(line.getProduct(), inv.getWarehouse(), MovementType.IN,
          item.qty(), "PURCHASE", po.getId());
    }
    po.completeIfFulfilled();
    return PurchaseMapper.toResponse(po);
  }
}
```

`purchase/PurchaseController.java`:
```java
package com.sample.inventory.purchase;

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
@RequestMapping("/api/v1/purchase-orders")
@RequiredArgsConstructor
public class PurchaseController {

  private static final Set<String> SORTABLE = Set.of("createdAt");
  private static final Sort DEFAULT_SORT = Sort.by("createdAt").descending();

  private final PurchaseService service;

  @PostMapping
  public ResponseEntity<ApiResponse<PurchaseResponse>> create(
      @Valid @RequestBody CreatePurchaseRequest req) {
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.create(req)));
  }

  @GetMapping
  public ResponseEntity<ApiResponse<PagedResult<PurchaseResponse>>> list(Pageable pageable) {
    var page = service.list(SortValidator.validated(pageable, SORTABLE, DEFAULT_SORT));
    return ResponseEntity.ok(ApiResponse.ok(PagedResult.from(page)));
  }

  @GetMapping("/{id}")
  public ResponseEntity<ApiResponse<PurchaseResponse>> get(@PathVariable long id) {
    return ResponseEntity.ok(ApiResponse.ok(service.get(id)));
  }

  @PostMapping("/{id}/receive")
  public ResponseEntity<ApiResponse<PurchaseResponse>> receive(
      @PathVariable long id, @Valid @RequestBody ReceiveRequest req) {
    return ResponseEntity.ok(ApiResponse.ok(service.receive(id, req)));
  }
}
```

- [ ] **Step 3: GREEN**

Run: `./mvnw -q -Dtest=PurchaseIT test` (JDK 21)
Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/sample/inventory/purchase src/main/java/com/sample/inventory/common/error/OverReceiveException.java src/test/java/com/sample/inventory/purchase
git commit -m "feat: add purchase orders with partial receiving"
```

### Task 4: Returns module

**Files:**
- Create: `common/error/ReturnExceededException.java`
- Create: `returns/ReturnOrder.java`, `returns/ReturnLine.java`, `returns/ReturnOrderRepository.java`, `returns/ReturnLineRepository.java`, `returns/CreateReturnRequest.java`, `returns/ReturnResponse.java`, `returns/ReturnMapper.java`, `returns/ReturnService.java`, `returns/ReturnController.java`
- Create test: `returns/ReturnIT.java`

**Interfaces:**
- Consumes: `AllocationRepository` (Task 1), `InventoryRepository.lockOne`, `MovementWriter`
- Produces: `POST/GET /api/v1/returns`

- [ ] **Step 1: Write IT first (exact)**

`src/test/java/com/sample/inventory/returns/ReturnIT.java`:

```java
package com.sample.inventory.returns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sample.inventory.TestcontainersConfiguration;
import com.sample.inventory.common.error.ReturnExceededException;
import com.sample.inventory.inventory.Inventory;
import com.sample.inventory.inventory.InventoryRepository;
import com.sample.inventory.movement.MovementType;
import com.sample.inventory.movement.StockMovementRepository;
import com.sample.inventory.order.AllocationRepository;
import com.sample.inventory.order.CreateOrderRequest;
import com.sample.inventory.order.CreateOrderRequest.CreateOrderLine;
import com.sample.inventory.order.OrderIdempotencyRepository;
import com.sample.inventory.order.OrderService;
import com.sample.inventory.order.ReservationRepository;
import com.sample.inventory.order.SalesOrderRepository;
import com.sample.inventory.product.Product;
import com.sample.inventory.product.ProductRepository;
import com.sample.inventory.returns.CreateReturnRequest.CreateReturnLine;
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
class ReturnIT {

  @Autowired ReturnService returns;
  @Autowired ReturnOrderRepository returnRepo;
  @Autowired OrderService orders;
  @Autowired SalesOrderRepository orderRepo;
  @Autowired AllocationRepository allocationRepo;
  @Autowired ReservationRepository reservationRepo;
  @Autowired OrderIdempotencyRepository idemRepo;
  @Autowired InventoryRepository invRepo;
  @Autowired ProductRepository productRepo;
  @Autowired WarehouseRepository warehouseRepo;
  @Autowired StockMovementRepository movementRepo;

  Product p;
  Warehouse w;
  long allocationId;

  @BeforeEach
  void seed() {
    movementRepo.deleteAll();
    returnRepo.deleteAll();
    reservationRepo.deleteAll();
    idemRepo.deleteAll();
    orderRepo.deleteAll();
    invRepo.deleteAll();
    p = productRepo.save(new Product("SKU-" + System.nanoTime(), "Keyboard"));
    w = warehouseRepo.save(new Warehouse("W-" + System.nanoTime(), "W", 1));
    invRepo.save(new Inventory(p, w, 5, 0, 1));
    var order = orders.create(
        new CreateOrderRequest(List.of(new CreateOrderLine(p.getId(), 3))), null, null);
    allocationId = order.lines().get(0).allocations().get(0).id();
  }

  @Test
  void returnRestocksOriginWarehouse() {
    var ret = returns.create(new CreateReturnRequest(
        List.of(new CreateReturnLine(allocationId, 2))));
    assertThat(ret.lines()).hasSize(1);
    assertThat(ret.lines().get(0).warehouseCode()).isEqualTo(w.getCode());
    var inv = invRepo.findByProduct_Id(p.getId()).get(0);
    assertThat(inv.getAvailable()).isEqualTo(4); // 5 - 3 reserved + 2 returned
    assertThat(inv.getReserved()).isEqualTo(3);
    assertThat(movementRepo.findAll()).extracting(m -> m.getType())
        .containsExactlyInAnyOrder(MovementType.RESERVE, MovementType.IN);
  }

  @Test
  void returnBeyondAllocatedIsRejected() {
    assertThatThrownBy(() -> returns.create(new CreateReturnRequest(
        List.of(new CreateReturnLine(allocationId, 4)))))
        .isInstanceOf(ReturnExceededException.class);
    assertThat(returnRepo.count()).isZero();
  }

  @Test
  void cumulativeReturnsAreCapped() {
    returns.create(new CreateReturnRequest(List.of(new CreateReturnLine(allocationId, 2))));
    assertThatThrownBy(() -> returns.create(new CreateReturnRequest(
        List.of(new CreateReturnLine(allocationId, 2)))))
        .isInstanceOf(ReturnExceededException.class);
    var inv = invRepo.findByProduct_Id(p.getId()).get(0);
    assertThat(inv.getAvailable()).isEqualTo(4);
  }
}
```

Note: delete order — `returnRepo.deleteAll()` BEFORE reservation/order deletes (return_line → allocation FK). ReturnLine has no cascade issues (ReturnOrder cascade ALL lines ✓). `returnRepo` needs no other wiring.

Run: `./mvnw -q -Dtest=ReturnIT test` → expect FAIL (compilation error).

- [ ] **Step 2: Implement (exact)**

`common/error/ReturnExceededException.java`:
```java
package com.sample.inventory.common.error;

public class ReturnExceededException extends DomainException {

  public ReturnExceededException(long allocationId) {
    super(ErrorCode.RETURN_EXCEEDED, allocationId);
  }
}
```
(`ErrorCode.RETURN_EXCEEDED` exists since Fase 1b — verify, do not duplicate.)

`returns/ReturnOrder.java`:
```java
package com.sample.inventory.returns;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "return_order")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReturnOrder {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<ReturnLine> lines = new ArrayList<>();

  public void addLine(ReturnLine line) {
    lines.add(line);
  }
}
```

`returns/ReturnLine.java`:
```java
package com.sample.inventory.returns;

import com.sample.inventory.order.Allocation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "return_line")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReturnLine {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "return_order_id", nullable = false)
  private ReturnOrder order;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "allocation_id", nullable = false)
  private Allocation allocation;

  @Column(nullable = false)
  private int qty;

  public ReturnLine(ReturnOrder order, Allocation allocation, int qty) {
    this.order = order;
    this.allocation = allocation;
    this.qty = qty;
  }
}
```

`returns/ReturnOrderRepository.java`:
```java
package com.sample.inventory.returns;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReturnOrderRepository extends JpaRepository<ReturnOrder, Long> {
}
```

`returns/ReturnLineRepository.java`:
```java
package com.sample.inventory.returns;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ReturnLineRepository extends JpaRepository<ReturnLine, Long> {

  @Query("select coalesce(sum(l.qty), 0) from ReturnLine l where l.allocation.id = :allocationId")
  int sumReturnedByAllocation(long allocationId);
}
```

`returns/CreateReturnRequest.java`:
```java
package com.sample.inventory.returns;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateReturnRequest(
    @Size(min = 1, max = 100) List<@Valid CreateReturnLine> lines) {

  public record CreateReturnLine(@NotNull Long allocationId, @Positive int qty) {}
}
```

`returns/ReturnResponse.java`:
```java
package com.sample.inventory.returns;

import java.time.Instant;
import java.util.List;

public record ReturnResponse(Long id, List<ReturnLineDto> lines, Instant createdAt) {

  public record ReturnLineDto(Long id, Long allocationId, Long productId, String productSku,
      Long warehouseId, String warehouseCode, int qty) {}
}
```

`returns/ReturnMapper.java`:
```java
package com.sample.inventory.returns;

public final class ReturnMapper {

  private ReturnMapper() {}

  public static ReturnResponse toResponse(ReturnOrder o) {
    return new ReturnResponse(o.getId(),
        o.getLines().stream().map(l -> new ReturnResponse.ReturnLineDto(l.getId(),
            l.getAllocation().getId(),
            l.getAllocation().getOrderLine().getProduct().getId(),
            l.getAllocation().getOrderLine().getProduct().getSku(),
            l.getAllocation().getWarehouse().getId(),
            l.getAllocation().getWarehouse().getCode(),
            l.getQty())).toList(),
        o.getCreatedAt());
  }
}
```

`returns/ReturnService.java`:
```java
package com.sample.inventory.returns;

import com.sample.inventory.common.error.NotFoundException;
import com.sample.inventory.common.error.ReturnExceededException;
import com.sample.inventory.inventory.InventoryRepository;
import com.sample.inventory.movement.MovementType;
import com.sample.inventory.movement.MovementWriter;
import com.sample.inventory.order.Allocation;
import com.sample.inventory.order.AllocationRepository;
import java.util.Comparator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReturnService {

  private final ReturnOrderRepository returnRepo;
  private final ReturnLineRepository lineRepo;
  private final AllocationRepository allocationRepo;
  private final InventoryRepository invRepo;
  private final MovementWriter movements;

  @Transactional
  public ReturnResponse create(CreateReturnRequest req) {
    var order = new ReturnOrder();
    var sorted = req.lines().stream()
        .sorted(Comparator.comparing(CreateReturnRequest.CreateReturnLine::allocationId))
        .toList();
    for (var line : sorted) {
      Allocation alloc = allocationRepo.findById(line.allocationId())
          .orElseThrow(() -> new NotFoundException("allocation", line.allocationId()));
      int already = lineRepo.sumReturnedByAllocation(alloc.getId());
      if (line.qty() + already > alloc.getQty()) {
        throw new ReturnExceededException(alloc.getId());
      }
      var inv = invRepo.lockOne(
              alloc.getOrderLine().getProduct().getId(), alloc.getWarehouse().getId())
          .orElseThrow(() -> new NotFoundException("inventory",
              alloc.getOrderLine().getProduct().getId() + "/" + alloc.getWarehouse().getId()));
      inv.add(line.qty());
      order.addLine(new ReturnLine(order, alloc, line.qty()));
    }
    returnRepo.saveAndFlush(order);
    for (var l : order.getLines()) {
      movements.write(l.getAllocation().getOrderLine().getProduct(),
          l.getAllocation().getWarehouse(), MovementType.IN, l.getQty(), "RETURN", order.getId());
    }
    return ReturnMapper.toResponse(order);
  }

  public ReturnResponse get(long id) {
    return returnRepo.findById(id).map(ReturnMapper::toResponse)
        .orElseThrow(() -> new NotFoundException("return", id));
  }

  public Page<ReturnResponse> list(Pageable pageable) {
    return returnRepo.findAll(pageable).map(ReturnMapper::toResponse);
  }
}
```
Note: lines sorted by allocationId = global lock order (single inventory row per allocation; distinct allocations → disjoint rows... two lines could share one allocation (cumulative across requests is separate txs; within one request duplicate allocationIds → two lockOne on SAME row in same tx = reentrant, safe). Good.
`ReturnMapper.toResponse(order)` after saveAndFlush — mapping inside tx ✓ (method is @Transactional; lazy loads fine).

`returns/ReturnController.java`:
```java
package com.sample.inventory.returns;

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
@RequestMapping("/api/v1/returns")
@RequiredArgsConstructor
public class ReturnController {

  private static final Set<String> SORTABLE = Set.of("createdAt");
  private static final Sort DEFAULT_SORT = Sort.by("createdAt").descending();

  private final ReturnService service;

  @PostMapping
  public ResponseEntity<ApiResponse<ReturnResponse>> create(
      @Valid @RequestBody CreateReturnRequest req) {
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.create(req)));
  }

  @GetMapping
  public ResponseEntity<ApiResponse<PagedResult<ReturnResponse>>> list(Pageable pageable) {
    var page = service.list(SortValidator.validated(pageable, SORTABLE, DEFAULT_SORT));
    return ResponseEntity.ok(ApiResponse.ok(PagedResult.from(page)));
  }

  @GetMapping("/{id}")
  public ResponseEntity<ApiResponse<ReturnResponse>> get(@PathVariable long id) {
    return ResponseEntity.ok(ApiResponse.ok(service.get(id)));
  }
}
```

- [ ] **Step 3: GREEN**

Run: `./mvnw -q -Dtest=ReturnIT test` (JDK 21)
Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/sample/inventory/returns src/main/java/com/sample/inventory/common/error/ReturnExceededException.java src/test/java/com/sample/inventory/returns
git commit -m "feat: add returns to origin warehouse with cumulative cap"
```

### Task 5: Verify + demo + README

**Files:**
- Modify: `README.md` (purchase + returns rows)

**Interfaces:**
- Consumes: everything (final gate Fase 2)
- Produces: green `verify`, live demo proof, updated README

- [ ] **Step 1: Full verify + format**

Run: `./mvnw spotless:apply` then `./mvnw verify` (JDK 21)
Expected: BUILD SUCCESS — surefire unit + failsafe `*IT` all green, `spotless:check` clean. Non-format changes from apply → NEEDS_CONTEXT, do not proceed.

- [ ] **Step 2: Live demo (dev profile, dev DB only, idempotent)**

1. Read `application.yml`; ensure configured DB reachable (do NOT wipe/reconfigure).
2. Start: `$env:SPRING_PROFILES_ACTIVE='dev'; ./mvnw spring-boot:run` (JDK 21), wait Started.
3. Purchase: create PO for seeded `KB-100` (get ids via `GET /api/v1/products?q=KB-100`, `GET /api/v1/warehouses`), receive partial then full → 200 + status COMPLETED.
4. Order + return: create order, then `POST /api/v1/returns` with its allocation → 201; `GET /api/v1/stock-movements` shows RESERVE + IN (+OUT if confirmed — skip confirm to keep demo focused... actually confirm it to show OUT too, then return AFTER confirm? Returns reference allocation regardless of order status per spec — demo: create → confirm → return → movements show RESERVE, OUT, IN).
5. Stop app. Record ALL outputs.

Seeded dev DB may lack inventory rows (receiving is new) — purchase receive CREATES inventory rows (orElseGet branch), so no psql needed. If `KB-100`/warehouses missing from dev DB (fresh volume), create via POST first.

- [ ] **Step 3: README + commit**

Append under `## API` (read README first, no duplication):

```markdown
| POST | /api/v1/purchase-orders | create PO (201) |
| GET | /api/v1/purchase-orders | paged, sort: createdAt |
| POST | /api/v1/purchase-orders/{id}/receive | partial/full receive (200), over-receive → 400 |
| POST | /api/v1/returns | return to origin warehouse (201), over-return → 400 |
| GET | /api/v1/returns | paged, sort: createdAt |
```

```bash
git add README.md
git commit -m "docs: document purchase and returns api"
```

## Plan Complete — Fase 3 Preview

Next plan (separate file): Kafka outbox + relay, low-stock publish on confirm/transfer, Redis read cache + invalidation, alert consumer stub. Plus Fase 1 follow-ups if still open: global lock order audit is DONE in Task 2; remaining polish (422 path test, post-expiry edge tests) folds into Fase 3 test tasks if desired.
