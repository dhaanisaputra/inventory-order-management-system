# Fase 1b (Order Slice) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Order slice yang concurrency-safe: split allocation by priority, reservation + expiry, idempotent confirm/cancel, movement history, dan read endpoints inventory/movement.

**Architecture:** Package-by-feature (`inventory/`, `order/`, `movement/`, plus `common/` existing). Satu `@Transactional` method per state transition; pessimistic lock selalu dalam urutan global (`warehouse.priority`, `warehouse.id`; lines diurut `productId`) — deadlock-free. Movement ditulis dalam transaksi yang sama via `MovementWriter(MANDATORY)`.

**Tech Stack:** Java 21, Spring Boot 4.1.1, PostgreSQL 16, Flyway, Testcontainers, JUnit 5 + Mockito + AssertJ.

## Global Constraints

- Branch `phase-one`; base package `com.sample.inventory`; API prefix `/api/v1`.
- JPA `ddl-auto: validate` — schema hanya dari Flyway (V1 ada; V2 di Task 3).
- Build JDK 21 (`$env:JAVA_HOME='C:\Program Files\Java\jdk-21'`); `mvn clean` tiap ganti toolchain.
- DTOs as records; entity `@Getter` + `@NoArgsConstructor(PROTECTED)` saja, tanpa `@Data`; `equals/hashCode` business key.
- Service return DTO, controller return `ResponseEntity<ApiResponse<…>>`; entity tidak bocor.
- `@Transactional` di service (`readOnly` default); `parallelStream` dilarang; tanpa H2.
- Boot 4.1.1 imports: `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest`, `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`, `org.springframework.test.context.bean.override.mockito.MockitoBean`.
- Testcontainers 2.x: `org.testcontainers.postgresql.PostgreSQLContainer` non-generic.
- `page` 0-based; `size` default 20 max 100 (yml sudah ada).
- Conventional Commits; `spotless:check` hijau; jangan push.

---

## File Structure

```
src/main/resources/db/migration/V2__order_idempotency.sql
src/main/resources/application.yml                    # + app.reservation.ttl
src/main/java/com/sample/inventory/
  InventoryOrderManagementSystemApplication.java       # + @EnableScheduling + @ConfigurationPropertiesScan (modify)
  common/error/ErrorCode.java                         # + 4 codes (modify)
  common/error/InvalidTransitionException.java        # create
  common/error/InsufficientStockException.java        # create
  common/error/IdempotencyConflictException.java      # create
  common/error/GlobalExceptionHandler.java            # + lock-timeout mapping (modify)
  inventory/Inventory.java, InventoryRepository.java,
    InventoryResponse.java, InventoryService.java (read-only),
    InventoryController.java
  order/OrderStatus.java, ReservationStatus.java,
    SalesOrder.java, OrderLine.java, Allocation.java, Reservation.java,
    OrderIdempotency.java, OrderIdempotencyRepository.java,
    SalesOrderRepository.java, ReservationRepository.java,
    CreateOrderRequest.java, OrderResponse.java,
    ReservationProperties.java, OrderService.java,
    ReservationExpiryService.java, ReservationExpiryWorker.java,
    OrderController.java
  movement/MovementType.java, StockMovement.java,
    StockMovementRepository.java, MovementWriter.java,
    MovementResponse.java, MovementController.java
src/test/.../inventory/InventoryRepositoryIT.java
src/test/.../order/OrderServiceTest.java (unit, Mockito),
  OrderCreateIT.java, OrderConfirmCancelIT.java,
  ReservationExpiryIT.java, OrderConcurrencyIT.java
src/test/.../movement/MovementControllerTest.java
src/test/.../product+warehouse update-branch + duplicate-race tests (Task 1)
```

### Task 1: Hardening master data (review gaps Fase 1a)

**Files:**
- Modify: `product/ProductService.java`, `warehouse/WarehouseService.java`
- Create test: `product/ProductUpdateTest.java`, `warehouse/WarehouseUpdateTest.java`

**Interfaces:**
- Consumes: existing services/repos, `DuplicateException`
- Produces: race-safe `create`, blank-tolerant `update` — order slice relies on stable master data

- [ ] **Step 1: Write failing tests first**

`src/test/java/com/sample/inventory/product/ProductUpdateTest.java`:

```java
package com.sample.inventory.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.sample.inventory.common.error.DuplicateException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class ProductUpdateTest {

  @Mock ProductRepository repo;
  @InjectMocks ProductService service;

  @Test
  void renameChangesName() {
    var p = new Product("SKU-1", "Old");
    when(repo.findById(1L)).thenReturn(Optional.of(p));
    var out = service.update(1L, new UpdateProductRequest("New", null));
    assertThat(out.name()).isEqualTo("New");
    assertThat(out.active()).isTrue();
  }

  @Test
  void blankNameIsIgnored() {
    var p = new Product("SKU-1", "Old");
    when(repo.findById(1L)).thenReturn(Optional.of(p));
    var out = service.update(1L, new UpdateProductRequest("  ", null));
    assertThat(out.name()).isEqualTo("Old");
  }

  @Test
  void deactivateSetsInactive() {
    var p = new Product("SKU-1", "Old");
    when(repo.findById(1L)).thenReturn(Optional.of(p));
    var out = service.update(1L, new UpdateProductRequest(null, false));
    assertThat(out.active()).isFalse();
  }

  @Test
  void concurrentDuplicateMapsToDuplicateException() {
    when(repo.existsBySku("SKU-1")).thenReturn(false);
    when(repo.save(any())).thenThrow(new DataIntegrityViolationException("dup"));
    assertThatThrownBy(() -> service.create(new ProductRequest("SKU-1", "x")))
        .isInstanceOf(DuplicateException.class);
  }
}
```

`src/test/java/com/sample/inventory/warehouse/WarehouseUpdateTest.java` (mirror, fields `code/JKT-1`, `reprioritize`):

```java
package com.sample.inventory.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.sample.inventory.common.error.DuplicateException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class WarehouseUpdateTest {

  @Mock WarehouseRepository repo;
  @InjectMocks WarehouseService service;

  @Test
  void reprioritizeChangesPriority() {
    var w = new Warehouse("JKT-1", "Jakarta", 10);
    when(repo.findById(1L)).thenReturn(Optional.of(w));
    var out = service.update(1L, new UpdateWarehouseRequest(null, 5));
    assertThat(out.priority()).isEqualTo(5);
  }

  @Test
  void blankNameIsIgnored() {
    var w = new Warehouse("JKT-1", "Jakarta", 10);
    when(repo.findById(1L)).thenReturn(Optional.of(w));
    var out = service.update(1L, new UpdateWarehouseRequest("  ", null));
    assertThat(out.name()).isEqualTo("Jakarta");
  }

  @Test
  void concurrentDuplicateMapsToDuplicateException() {
    when(repo.existsByCode("JKT-1")).thenReturn(false);
    when(repo.save(any())).thenThrow(new DataIntegrityViolationException("dup"));
    assertThatThrownBy(() -> service.create(new WarehouseRequest("JKT-1", "Jakarta", 10)))
        .isInstanceOf(DuplicateException.class);
  }
}
```

Run: `./mvnw -q -Dtest='ProductUpdateTest,WarehouseUpdateTest' test`
Expected: FAIL — `blankNameIsIgnored` (2) + `concurrentDuplicateMapsToDuplicateException` (2) fail; rename/reprioritize pass.

- [ ] **Step 2: Harden both services (minimal diff)**

In `ProductService.create`, wrap save:

```java
  @Transactional
  public ProductResponse create(ProductRequest req) {
    if (repo.existsBySku(req.sku())) {
      throw new DuplicateException("product", req.sku());
    }
    try {
      return ProductMapper.toResponse(repo.save(new Product(req.sku(), req.name())));
    } catch (DataIntegrityViolationException e) {
      throw new DuplicateException("product", req.sku());
    }
  }
```

Add import `org.springframework.dao.DataIntegrityViolationException`. Same for `WarehouseService.create` with `existsByCode`/`"warehouse"`.

In `ProductService.update`, change guard to:

```java
    if (req.name() != null && !req.name().isBlank()) {
      p.rename(req.name().strip());
    }
```

Same for `WarehouseService.update` (`w.rename(req.name().strip())`).

- [ ] **Step 3: Run to verify GREEN**

Run: `./mvnw -q -Dtest='ProductUpdateTest,WarehouseUpdateTest,ProductServiceTest,WarehouseServiceTest' test`
Expected: `Tests run: 11, Failures: 0, Errors: 0`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/sample/inventory/product/ProductService.java src/main/java/com/sample/inventory/warehouse/WarehouseService.java src/test/java/com/sample/inventory/product/ProductUpdateTest.java src/test/java/com/sample/inventory/warehouse/WarehouseUpdateTest.java
git commit -m "fix: harden master data update and duplicate race"
```

### Task 2: Order domain (enums, entities, repositories)

**Files:**
- Create: `order/OrderStatus.java`, `order/ReservationStatus.java`, `movement/MovementType.java`
- Create: `inventory/Inventory.java`, `inventory/InventoryRepository.java`
- Create: `order/SalesOrder.java`, `order/OrderLine.java`, `order/Allocation.java`, `order/Reservation.java`, `order/SalesOrderRepository.java`, `order/ReservationRepository.java`
- Create: `movement/StockMovement.java`, `movement/StockMovementRepository.java`
- Create test: `inventory/InventoryRepositoryIT.java`

**Interfaces:**
- Consumes: Task 2 V1 tables; `Product`, `Warehouse` entities
- Produces: `InventoryRepository.lockAvailable/lockOne`, `ReservationRepository.lockDue`, entity domain methods used by Tasks 4–7

- [ ] **Step 1: Write failing IT first**

`src/test/java/com/sample/inventory/inventory/InventoryRepositoryIT.java`:

```java
package com.sample.inventory.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.sample.inventory.TestcontainersConfiguration;
import com.sample.inventory.product.Product;
import com.sample.inventory.product.ProductRepository;
import com.sample.inventory.warehouse.Warehouse;
import com.sample.inventory.warehouse.WarehouseRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
class InventoryRepositoryIT {

  @Autowired InventoryRepository invRepo;
  @Autowired ProductRepository productRepo;
  @Autowired WarehouseRepository warehouseRepo;

  @Test
  void lockAvailableReturnsPriorityOrder() {
    var p = productRepo.save(new Product("SKU-1", "Keyboard"));
    var low = warehouseRepo.save(new Warehouse("LOW", "Low", 50));
    var high = warehouseRepo.save(new Warehouse("HIGH", "High", 5));
    invRepo.save(new Inventory(p, low, 10, 0, 0));
    invRepo.save(new Inventory(p, high, 10, 0, 0));
    List<Inventory> got = invRepo.lockAvailable(p.getId());
    assertThat(got).extracting(i -> i.getWarehouse().getCode())
        .containsExactly("HIGH", "LOW");
  }

  @Test
  void lockAvailableSkipsEmptyStock() {
    var p = productRepo.save(new Product("SKU-2", "Mouse"));
    var w = warehouseRepo.save(new Warehouse("W", "W", 1));
    invRepo.save(new Inventory(p, w, 0, 0, 0));
    assertThat(invRepo.lockAvailable(p.getId())).isEmpty();
  }
}
```

Run: `./mvnw -q -Dtest=InventoryRepositoryIT test`
Expected: FAIL — compilation error (classes do not exist).

- [ ] **Step 2: Write enums + entities + repositories (exact)**

`order/OrderStatus.java`:

```java
package com.sample.inventory.order;

public enum OrderStatus {
  PENDING,
  CONFIRMED,
  CANCELLED
}
```

`order/ReservationStatus.java`:

```java
package com.sample.inventory.order;

public enum ReservationStatus {
  ACTIVE,
  CONFIRMED,
  CANCELLED,
  EXPIRED
}
```

`movement/MovementType.java`:

```java
package com.sample.inventory.movement;

public enum MovementType {
  IN,
  OUT,
  RESERVE,
  RELEASE,
  TRANSFER
}
```

`inventory/Inventory.java`:

```java
package com.sample.inventory.inventory;

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
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "inventory")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inventory {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "product_id", nullable = false)
  private Product product;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "warehouse_id", nullable = false)
  private Warehouse warehouse;

  @Column(nullable = false)
  private int available;

  @Column(nullable = false)
  private int reserved;

  @Column(name = "low_stock_threshold", nullable = false)
  private int lowStockThreshold;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public Inventory(Product product, Warehouse warehouse, int available, int reserved, int lowStockThreshold) {
    this.product = product;
    this.warehouse = warehouse;
    this.available = available;
    this.reserved = reserved;
    this.lowStockThreshold = lowStockThreshold;
  }

  public void reserve(int qty) {
    if (qty <= 0 || qty > available) {
      throw new IllegalArgumentException("cannot reserve " + qty + ", available=" + available);
    }
    available -= qty;
    reserved += qty;
  }

  public void confirm(int qty) {
    if (qty <= 0 || qty > reserved) {
      throw new IllegalArgumentException("cannot confirm " + qty + ", reserved=" + reserved);
    }
    reserved -= qty;
  }

  public void release(int qty) {
    if (qty <= 0 || qty > reserved) {
      throw new IllegalArgumentException("cannot release " + qty + ", reserved=" + reserved);
    }
    reserved -= qty;
    available += qty;
  }

  public void add(int qty) {
    if (qty <= 0) {
      throw new IllegalArgumentException("qty must be positive");
    }
    available += qty;
  }

  public boolean isLowStock() {
    return available < lowStockThreshold;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Inventory other)) {
      return false;
    }
    return id != null && Objects.equals(id, other.id);
  }

  @Override
  public int hashCode() {
    return getClass().hashCode();
  }
}
```

`inventory/InventoryRepository.java`:

```java
package com.sample.inventory.inventory;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
  @Query("select i from Inventory i where i.product.id = :productId and i.available > 0"
      + " order by i.warehouse.priority asc, i.warehouse.id asc")
  List<Inventory> lockAvailable(long productId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
  @Query("select i from Inventory i where i.product.id = :productId and i.warehouse.id = :warehouseId")
  Optional<Inventory> lockOne(long productId, long warehouseId);

  @EntityGraph(attributePaths = {"product", "warehouse"})
  @Query("select i from Inventory i where (:productId is null or i.product.id = :productId)"
      + " and (:warehouseId is null or i.warehouse.id = :warehouseId)"
      + " and (:lowOnly = false or i.available < i.lowStockThreshold)")
  Page<Inventory> search(Long productId, Long warehouseId, boolean lowOnly, Pageable pageable);
}
```

`order/SalesOrder.java`:

```java
package com.sample.inventory.order;

import com.sample.inventory.common.error.InvalidTransitionException;
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
@Table(name = "sales_order")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SalesOrder {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private OrderStatus status = OrderStatus.PENDING;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<OrderLine> lines = new ArrayList<>();

  public void addLine(OrderLine line) {
    lines.add(line);
  }

  public void confirm() {
    if (status != OrderStatus.PENDING) {
      throw new InvalidTransitionException(id, status);
    }
    status = OrderStatus.CONFIRMED;
  }

  public void cancel() {
    if (status != OrderStatus.PENDING) {
      throw new InvalidTransitionException(id, status);
    }
    status = OrderStatus.CANCELLED;
  }
}
```

`order/OrderLine.java`:

```java
package com.sample.inventory.order;

import com.sample.inventory.product.Product;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "order_line")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderLine {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "sales_order_id", nullable = false)
  private SalesOrder order;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "product_id", nullable = false)
  private Product product;

  @Column(nullable = false)
  private int qty;

  @OneToMany(mappedBy = "orderLine", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<Allocation> allocations = new ArrayList<>();

  public OrderLine(SalesOrder order, Product product, int qty) {
    this.order = order;
    this.product = product;
    this.qty = qty;
  }

  public void addAllocation(Allocation allocation) {
    allocations.add(allocation);
  }
}
```

`order/Allocation.java`:

```java
package com.sample.inventory.order;

import com.sample.inventory.warehouse.Warehouse;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "allocation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Allocation {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "order_line_id", nullable = false)
  private OrderLine orderLine;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "warehouse_id", nullable = false)
  private Warehouse warehouse;

  @Column(nullable = false)
  private int qty;

  @OneToOne(mappedBy = "allocation", fetch = FetchType.LAZY)
  private Reservation reservation;

  public Allocation(OrderLine orderLine, Warehouse warehouse, int qty) {
    this.orderLine = orderLine;
    this.warehouse = warehouse;
    this.qty = qty;
  }
}
```

`order/Reservation.java`:

```java
package com.sample.inventory.order;

import com.sample.inventory.common.error.InvalidTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "reservation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @OneToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "allocation_id", nullable = false, unique = true)
  private Allocation allocation;

  @Column(nullable = false)
  private int qty;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private ReservationStatus status = ReservationStatus.ACTIVE;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public Reservation(Allocation allocation, int qty, Instant expiresAt) {
    this.allocation = allocation;
    this.qty = qty;
    this.expiresAt = expiresAt;
  }

  public void confirm() {
    requireActive();
    status = ReservationStatus.CONFIRMED;
  }

  public void cancel() {
    requireActive();
    status = ReservationStatus.CANCELLED;
  }

  public void expire() {
    requireActive();
    status = ReservationStatus.EXPIRED;
  }

  private void requireActive() {
    if (status != ReservationStatus.ACTIVE) {
      throw new InvalidTransitionException(null, status);
    }
  }
}
```

`order/SalesOrderRepository.java`:

```java
package com.sample.inventory.order;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SalesOrderRepository extends JpaRepository<SalesOrder, Long> {

  @EntityGraph(attributePaths = {
      "lines", "lines.product",
      "lines.allocations", "lines.allocations.warehouse", "lines.allocations.reservation"
  })
  @Query("select o from SalesOrder o where o.id = :id")
  Optional<SalesOrder> findDetailedById(long id);

  @Query("select o from SalesOrder o where (:status is null or o.status = :status)"
      + " and (:from is null or o.createdAt >= :from)"
      + " and (:to is null or o.createdAt <= :to)")
  Page<SalesOrder> search(OrderStatus status, Instant from, Instant to, Pageable pageable);
}
```

`order/ReservationRepository.java`:

```java
package com.sample.inventory.order;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

  @Query(value = "SELECT * FROM reservation WHERE status = 'ACTIVE' AND expires_at <= :now"
      + " ORDER BY expires_at LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
  List<Reservation> lockDue(Instant now, int limit);
}
```

`movement/StockMovement.java`:

```java
package com.sample.inventory.movement;

import com.sample.inventory.product.Product;
import com.sample.inventory.warehouse.Warehouse;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "stock_movement")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockMovement {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "product_id", nullable = false)
  private Product product;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "warehouse_id", nullable = false)
  private Warehouse warehouse;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private MovementType type;

  @Column(nullable = false)
  private int qty;

  @Column(name = "ref_type", nullable = false, length = 32)
  private String refType;

  @Column(name = "ref_id", nullable = false)
  private long refId;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public static StockMovement of(Product product, Warehouse warehouse, MovementType type,
      int qty, String refType, long refId) {
    StockMovement m = new StockMovement();
    m.product = product;
    m.warehouse = warehouse;
    m.type = type;
    m.qty = qty;
    m.refType = refType;
    m.refId = refId;
    return m;
  }
}
```

`movement/StockMovementRepository.java`:

```java
package com.sample.inventory.movement;

import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

  @EntityGraph(attributePaths = {"product", "warehouse"})
  @Query("select m from StockMovement m where (:productId is null or m.product.id = :productId)"
      + " and (:warehouseId is null or m.warehouse.id = :warehouseId)"
      + " and (:type is null or m.type = :type)"
      + " and (:from is null or m.createdAt >= :from)"
      + " and (:to is null or m.createdAt <= :to)")
  Page<StockMovement> search(Long productId, Long warehouseId, MovementType type,
      Instant from, Instant to, Pageable pageable);
}
```

- [ ] **Step 3: Run IT → expect GREEN**

Run: `./mvnw -q -Dtest=InventoryRepositoryIT test` (JDK 21)
Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/sample/inventory/inventory src/main/java/com/sample/inventory/order src/main/java/com/sample/inventory/movement src/test/java/com/sample/inventory/inventory
git commit -m "feat: add order domain entities and repositories"
```

### Task 3: Errors, idempotency schema, properties

**Files:**
- Modify: `common/error/ErrorCode.java`, `common/error/GlobalExceptionHandler.java`, `src/main/resources/application.yml`
- Create: `common/error/InvalidTransitionException.java`, `common/error/InsufficientStockException.java`, `common/error/IdempotencyConflictException.java`
- Create: `order/OrderIdempotency.java`, `order/OrderIdempotencyRepository.java`, `order/ReservationProperties.java`
- Create: `src/main/resources/db/migration/V2__order_idempotency.sql`
- Create test: `common/error/ErrorCodeTest.java`

**Interfaces:**
- Consumes: Task 4 Fase 1a handler
- Produces: new codes + exceptions + `ReservationProperties.ttl()` + `order_idempotency` table for Tasks 5–7

- [ ] **Step 1: Write failing test first**

`src/test/java/com/sample/inventory/common/error/ErrorCodeTest.java`:

```java
package com.sample.inventory.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ErrorCodeTest {

  @Test
  void formatsNewCodes() {
    assertThat(ErrorCode.INSUFFICIENT_STOCK.httpStatus()).isEqualTo(409);
    assertThat(ErrorCode.INSUFFICIENT_STOCK.format("KB-100"))
        .isEqualTo("stock insufficient for product KB-100");
    assertThat(ErrorCode.STOCK_CONTENTION.httpStatus()).isEqualTo(409);
    assertThat(ErrorCode.INVALID_TRANSITION.format(7L, "CONFIRMED"))
        .isEqualTo("order 7 cannot transition from CONFIRMED");
    assertThat(ErrorCode.IDEMPOTENCY_KEY_CONFLICT.httpStatus()).isEqualTo(422);
  }
}
```

Run: `./mvnw -q -Dtest=ErrorCodeTest test`
Expected: FAIL — compilation error (codes do not exist).

- [ ] **Step 2: Implement (exact)**

Append to `ErrorCode` enum (after `INTERNAL(500, "internal error")` — keep existing first):

```java
  INSUFFICIENT_STOCK(409, "stock insufficient for product {0}"),
  STOCK_CONTENTION(409, "stock contention, please retry"),
  INVALID_TRANSITION(409, "order {0} cannot transition from {1}"),
  IDEMPOTENCY_KEY_CONFLICT(422, "idempotency key reused with different payload"),
```

`common/error/InvalidTransitionException.java`:

```java
package com.sample.inventory.common.error;

public class InvalidTransitionException extends DomainException {

  public InvalidTransitionException(Object orderId, Object status) {
    super(ErrorCode.INVALID_TRANSITION, orderId, status);
  }
}
```

`common/error/InsufficientStockException.java`:

```java
package com.sample.inventory.common.error;

public class InsufficientStockException extends DomainException {

  public InsufficientStockException(String sku) {
    super(ErrorCode.INSUFFICIENT_STOCK, sku);
  }
}
```

`common/error/IdempotencyConflictException.java`:

```java
package com.sample.inventory.common.error;

public class IdempotencyConflictException extends DomainException {

  public IdempotencyConflictException() {
    super(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
  }
}
```

In `GlobalExceptionHandler`, add (imports `jakarta.persistence.LockTimeoutException`, `jakarta.persistence.PessimisticLockException`):

```java
  @ExceptionHandler({PessimisticLockException.class, LockTimeoutException.class})
  public ResponseEntity<ApiResponse<Void>> handleContention(RuntimeException ex) {
    return ResponseEntity.status(ErrorCode.STOCK_CONTENTION.httpStatus())
        .body(ApiResponse.fail(ErrorCode.STOCK_CONTENTION.name(), ErrorCode.STOCK_CONTENTION.format()));
  }
```

`order/OrderIdempotency.java`:

```java
package com.sample.inventory.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
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
@Table(name = "order_idempotency")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderIdempotency {

  @Id
  @Column(name = "idem_key", nullable = false, length = 64)
  private String key;

  @Column(name = "request_hash", nullable = false, length = 64)
  private String requestHash;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "order_id", nullable = false)
  private SalesOrder order;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public OrderIdempotency(String key, String requestHash, SalesOrder order) {
    this.key = key;
    this.requestHash = requestHash;
    this.order = order;
  }
}
```

`order/OrderIdempotencyRepository.java`:

```java
package com.sample.inventory.order;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderIdempotencyRepository extends JpaRepository<OrderIdempotency, String> {
}
```

`order/ReservationProperties.java`:

```java
package com.sample.inventory.order;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.reservation")
public record ReservationProperties(Duration ttl) {
}
```

`V2__order_idempotency.sql` (exact, never edit after merge):

```sql
CREATE TABLE order_idempotency (
  idem_key VARCHAR(64) PRIMARY KEY,
  request_hash VARCHAR(64) NOT NULL,
  order_id BIGINT NOT NULL REFERENCES sales_order (id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Append to `application.yml` as a TOP-LEVEL block (NOT under `spring:`, exact — prefix is `app.*`):

```yaml
app:
  reservation:
    ttl: PT30M
```

- [ ] **Step 3: Run GREEN**

Run: `./mvnw -q -Dtest=ErrorCodeTest test`
Expected: `Tests run: 1, Failures: 0, Errors: 0`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/sample/inventory/common/error src/main/java/com/sample/inventory/order/OrderIdempotency.java src/main/java/com/sample/inventory/order/OrderIdempotencyRepository.java src/main/java/com/sample/inventory/order/ReservationProperties.java src/main/resources/db/migration/V2__order_idempotency.sql src/main/resources/application.yml src/test/java/com/sample/inventory/common/error
git commit -m "feat: add order error codes, idempotency schema, reservation ttl"
```

### Task 4: Movement writer + inventory/movement reads

**Files:**
- Create: `movement/MovementWriter.java`, `movement/MovementResponse.java`, `movement/MovementMapper.java`, `movement/MovementController.java`
- Create: `inventory/InventoryResponse.java`, `inventory/InventoryMapper.java`, `inventory/InventoryService.java`, `inventory/InventoryController.java`
- Create test: `movement/MovementControllerTest.java`

**Interfaces:**
- Consumes: `StockMovementRepository.search`, `InventoryRepository.search`, common web/error
- Produces: `MovementWriter.write` (MANDATORY, used by Tasks 5–7); `GET /api/v1/inventories`, `GET /api/v1/stock-movements`

- [ ] **Step 1: Write controller test first (exact)**

`src/test/java/com/sample/inventory/movement/MovementControllerTest.java`:

```java
package com.sample.inventory.movement;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MovementController.class)
class MovementControllerTest {

  @Autowired MockMvc mvc;
  @MockitoBean MovementService service;

  @Test
  void searchReturnsPagedMovements() throws Exception {
    var dto = new MovementResponse(1L, 2L, "KB-100", 3L, "JKT-1",
        MovementType.RESERVE, 5, "ORDER", 9L, Instant.parse("2026-09-11T00:00:00Z"));
    var page = new PageImpl<>(List.of(dto));
    when(service.search(isNull(), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
        .thenReturn(page);
    mvc.perform(get("/api/v1/stock-movements"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].type").value("RESERVE"))
        .andExpect(jsonPath("$.data.totalElements").value(1));
  }
}
```

Run: `./mvnw -q -Dtest=MovementControllerTest test`
Expected: FAIL — compilation error (classes do not exist).

- [ ] **Step 2: Implement (exact)**

`movement/MovementWriter.java`:

```java
package com.sample.inventory.movement;

import com.sample.inventory.product.Product;
import com.sample.inventory.warehouse.Warehouse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MovementWriter {

  private final StockMovementRepository repo;

  @Transactional(propagation = Propagation.MANDATORY)
  public void write(Product product, Warehouse warehouse, MovementType type,
      int qty, String refType, long refId) {
    repo.save(StockMovement.of(product, warehouse, type, qty, refType, refId));
  }
}
```

`movement/MovementResponse.java`:

```java
package com.sample.inventory.movement;

import java.time.Instant;

public record MovementResponse(Long id, Long productId, String productSku, Long warehouseId,
    String warehouseCode, MovementType type, int qty, String refType, long refId, Instant createdAt) {}
```

`movement/MovementMapper.java`:

```java
package com.sample.inventory.movement;

public final class MovementMapper {

  private MovementMapper() {}

  public static MovementResponse toResponse(StockMovement m) {
    return new MovementResponse(m.getId(), m.getProduct().getId(), m.getProduct().getSku(),
        m.getWarehouse().getId(), m.getWarehouse().getCode(), m.getType(), m.getQty(),
        m.getRefType(), m.getRefId(), m.getCreatedAt());
  }
}
```

`movement/MovementService.java` (read-only; needed by controller — test mocks it):

```java
package com.sample.inventory.movement;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MovementService {

  private final StockMovementRepository repo;

  public Page<MovementResponse> search(Long productId, Long warehouseId, MovementType type,
      Instant from, Instant to, Pageable pageable) {
    return repo.search(productId, warehouseId, type, from, to, pageable).map(MovementMapper::toResponse);
  }
}
```

`movement/MovementController.java`:

```java
package com.sample.inventory.movement;

import com.sample.inventory.common.error.DomainException;
import com.sample.inventory.common.error.ErrorCode;
import com.sample.inventory.common.web.ApiResponse;
import com.sample.inventory.common.web.PagedResult;
import com.sample.inventory.common.web.SortValidator;
import java.time.Instant;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/stock-movements")
@RequiredArgsConstructor
public class MovementController {

  private static final Set<String> SORTABLE = Set.of("createdAt");
  private static final Sort DEFAULT_SORT = Sort.by("createdAt").descending();

  private final MovementService service;

  @GetMapping
  public ResponseEntity<ApiResponse<PagedResult<MovementResponse>>> search(
      @RequestParam(required = false) Long productId,
      @RequestParam(required = false) Long warehouseId,
      @RequestParam(required = false) MovementType type,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      Pageable pageable) {
    if (from != null && to != null && from.isAfter(to)) {
      throw new DomainException(ErrorCode.VALIDATION);
    }
    var page = service.search(productId, warehouseId, type, from, to,
        SortValidator.validated(pageable, SORTABLE, DEFAULT_SORT));
    return ResponseEntity.ok(ApiResponse.ok(PagedResult.from(page)));
  }
}
```

imports `com.sample.inventory.common.error.DomainException`, `com.sample.inventory.common.error.ErrorCode`.

`inventory/InventoryResponse.java`:

```java
package com.sample.inventory.inventory;

public record InventoryResponse(Long id, Long productId, String productSku, Long warehouseId,
    String warehouseCode, int available, int reserved, int lowStockThreshold, boolean lowStock) {}
```

`inventory/InventoryMapper.java`:

```java
package com.sample.inventory.inventory;

public final class InventoryMapper {

  private InventoryMapper() {}

  public static InventoryResponse toResponse(Inventory i) {
    return new InventoryResponse(i.getId(), i.getProduct().getId(), i.getProduct().getSku(),
        i.getWarehouse().getId(), i.getWarehouse().getCode(), i.getAvailable(), i.getReserved(),
        i.getLowStockThreshold(), i.isLowStock());
  }
}
```

`inventory/InventoryService.java`:

```java
package com.sample.inventory.inventory;

import com.sample.inventory.common.error.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryService {

  private final InventoryRepository repo;

  public InventoryResponse get(long id) {
    return repo.findById(id).map(InventoryMapper::toResponse)
        .orElseThrow(() -> new NotFoundException("inventory", id));
  }

  public Page<InventoryResponse> search(Long productId, Long warehouseId, boolean lowOnly,
      Pageable pageable) {
    return repo.search(productId, warehouseId, lowOnly, pageable).map(InventoryMapper::toResponse);
  }
}
```

Wait — `search` without EntityGraph on findById: `get` maps product/warehouse lazily → LazyInitializationException outside tx! Service is `@Transactional(readOnly=true)` → mapping happens INSIDE tx (method body). `.map()` runs inside the transactional method. OK fine.

But `search` in repo HAS @EntityGraph — good. `findById` default no graph; mapping inside tx OK.

`inventory/InventoryController.java`:

```java
package com.sample.inventory.inventory;

import com.sample.inventory.common.web.ApiResponse;
import com.sample.inventory.common.web.PagedResult;
import com.sample.inventory.common.web.SortValidator;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inventories")
@RequiredArgsConstructor
public class InventoryController {

  private static final Set<String> SORTABLE = Set.of("available", "reserved", "createdAt");
  private static final Sort DEFAULT_SORT = Sort.by("id").ascending();

  private final InventoryService service;

  @GetMapping
  public ResponseEntity<ApiResponse<PagedResult<InventoryResponse>>> search(
      @RequestParam(required = false) Long productId,
      @RequestParam(required = false) Long warehouseId,
      @RequestParam(defaultValue = "false") boolean lowStockOnly,
      Pageable pageable) {
    var page = service.search(productId, warehouseId, lowStockOnly,
        SortValidator.validated(pageable, SORTABLE, DEFAULT_SORT));
    return ResponseEntity.ok(ApiResponse.ok(PagedResult.from(page)));
  }

  @GetMapping("/{id}")
  public ResponseEntity<ApiResponse<InventoryResponse>> get(@PathVariable long id) {
    return ResponseEntity.ok(ApiResponse.ok(service.get(id)));
  }
}
```

- [ ] **Step 3: Run GREEN**

Run: `./mvnw -q -Dtest=MovementControllerTest test` (JDK 21)
Expected: `Tests run: 1, Failures: 0, Errors: 0`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/sample/inventory/movement src/main/java/com/sample/inventory/inventory src/test/java/com/sample/inventory/movement
git commit -m "feat: add movement writer and inventory read endpoints"
```

### Task 5: Order create (split + reserve + idempotency)

**Files:**
- Create: `order/CreateOrderRequest.java`, `order/OrderResponse.java`, `order/OrderMapper.java`, `order/RequestHash.java`, `order/OrderService.java`, `order/OrderController.java`
- Create test: `order/OrderCreateIT.java`

**Interfaces:**
- Consumes: `InventoryRepository.lockAvailable`, `MovementWriter.write`, `ReservationProperties.ttl()`, `SalesOrderRepository`, `OrderIdempotencyRepository`, `Clock` bean, exceptions from Task 3
- Produces: `OrderService.create/get`, `POST /api/v1/orders` — Tasks 6–8 build on it

- [ ] **Step 1: Write failing IT first**

`src/test/java/com/sample/inventory/order/OrderCreateIT.java`:

```java
package com.sample.inventory.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sample.inventory.TestcontainersConfiguration;
import com.sample.inventory.common.error.InsufficientStockException;
import com.sample.inventory.inventory.Inventory;
import com.sample.inventory.inventory.InventoryRepository;
import com.sample.inventory.movement.MovementType;
import com.sample.inventory.movement.StockMovementRepository;
import com.sample.inventory.order.CreateOrderRequest.CreateOrderLine;
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
class OrderCreateIT {

  @Autowired OrderService orders;
  @Autowired SalesOrderRepository orderRepo;
  @Autowired ReservationRepository reservationRepo;
  @Autowired InventoryRepository invRepo;
  @Autowired ProductRepository productRepo;
  @Autowired WarehouseRepository warehouseRepo;
  @Autowired StockMovementRepository movementRepo;

  Product p;
  Warehouse high;
  Warehouse low;

  @BeforeEach
  void seed() {
    movementRepo.deleteAll();
    reservationRepo.deleteAll();
    orderRepo.deleteAll();
    invRepo.deleteAll();
    p = productRepo.save(new Product("SKU-" + System.nanoTime(), "Keyboard"));
    high = warehouseRepo.save(new Warehouse("H-" + System.nanoTime(), "High", 5));
    low = warehouseRepo.save(new Warehouse("L-" + System.nanoTime(), "Low", 50));
    invRepo.save(new Inventory(p, high, 3, 0, 1));
    invRepo.save(new Inventory(p, low, 10, 0, 1));
  }

  @Test
  void splitsByPriorityAndReserves() {
    var res = orders.create(new CreateOrderRequest(List.of(new CreateOrderLine(p.getId(), 5))), null, null);
    assertThat(res.status()).isEqualTo(OrderStatus.PENDING);
    assertThat(res.lines()).hasSize(1);
    var allocs = res.lines().get(0).allocations();
    assertThat(allocs).hasSize(2);
    assertThat(allocs.get(0).warehouseCode()).isEqualTo(high.getCode());
    assertThat(allocs.get(0).qty()).isEqualTo(3);
    assertThat(allocs.get(1).warehouseCode()).isEqualTo(low.getCode());
    assertThat(allocs.get(1).qty()).isEqualTo(2);
    var inv = invRepo.lockAvailable(p.getId());
    assertThat(inv).extracting(i -> i.getAvailable() + i.getReserved()))
        .containsExactlyInAnyOrder(3, 10); // stock conserved: avail+reserved unchanged
    assertThat(movementRepo.count()).isEqualTo(2);
    assertThat(movementRepo.findAll()).extracting(m -> m.getType())
        .containsOnly(MovementType.RESERVE);
  }

  @Test
  void insufficientStockRollsBackFully() {
    assertThatThrownBy(() -> orders.create(
        new CreateOrderRequest(List.of(new CreateOrderLine(p.getId(), 99))), null, null))
        .isInstanceOf(InsufficientStockException.class);
    assertThat(orderRepo.count()).isZero();
    assertThat(movementRepo.count()).isZero();
    assertThat(invRepo.lockAvailable(p.getId()))
        .extracting(i -> i.getReserved()).containsOnly(0);
  }

  @Test
  void idempotencyKeyReplaysSameOrder() {
    var req = new CreateOrderRequest(List.of(new CreateOrderLine(p.getId(), 2)));
    var first = orders.create(req, "key-1", RequestHash.of(req));
    var second = orders.create(req, "key-1", RequestHash.of(req));
    assertThat(second.id()).isEqualTo(first.id());
    assertThat(orderRepo.count()).isEqualTo(1);
  }
}
```

Note: `deleteAll` order in `@BeforeEach` must respect FKs: movement → reservation → allocation → order_line → sales_order → inventory → product/warehouse. `movementRepo.deleteAll()` then `orderRepo.deleteAll()` cascades lines/allocations? Cascade ALL + orphanRemoval on lines/allocations handles children; reservations reference allocations (no cascade from allocation side!) — `reservation` rows block allocation delete. Order of deletes: movement, reservation, order (cascades line+allocation), inventory. But ReservationRepository isn't autowired... Add `@Autowired ReservationRepository reservationRepo;` + `reservationRepo.deleteAll()` after movements. Include in final file (the snippet above omits it — implementer: ADD the field + delete line).

Also `extracting(a -> a.warehouseCode())` — OrderResponse shape (see Step 2): `AllocationDto(warehouseId, warehouseCode, qty)`. OK.

Run: `./mvnw -q -Dtest=OrderCreateIT test`
Expected: FAIL — compilation error.

- [ ] **Step 2: Implement (exact)**

`order/CreateOrderRequest.java`:

```java
package com.sample.inventory.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateOrderRequest(
    @Size(min = 1, max = 100) List<@Valid CreateOrderLine> lines) {

  public record CreateOrderLine(@NotNull Long productId, @Positive int qty) {}
}
```

`order/OrderResponse.java`:

```java
package com.sample.inventory.order;

import java.time.Instant;
import java.util.List;

public record OrderResponse(Long id, OrderStatus status, List<OrderLineDto> lines, Instant createdAt) {

  public record OrderLineDto(Long id, Long productId, String productSku, int qty,
      List<AllocationDto> allocations) {}

  public record AllocationDto(Long id, Long warehouseId, String warehouseCode, int qty) {}
}
```

`order/OrderMapper.java`:

```java
package com.sample.inventory.order;

public final class OrderMapper {

  private OrderMapper() {}

  public static OrderResponse toResponse(SalesOrder o) {
    return new OrderResponse(o.getId(), o.getStatus(),
        o.getLines().stream().map(l -> new OrderResponse.OrderLineDto(l.getId(),
            l.getProduct().getId(), l.getProduct().getSku(), l.getQty(),
            l.getAllocations().stream().map(a -> new OrderResponse.AllocationDto(a.getId(),
                a.getWarehouse().getId(), a.getWarehouse().getCode(), a.getQty())).toList()))
            .toList(),
        o.getCreatedAt());
  }
}
```

`order/RequestHash.java`:

```java
package com.sample.inventory.order;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;

public final class RequestHash {

  private RequestHash() {}

  public static String of(CreateOrderRequest req) {
    try {
      var canonical = req.lines().stream()
          .sorted(Comparator.comparing(CreateOrderRequest.CreateOrderLine::productId))
          .map(l -> l.productId() + ":" + l.qty())
          .reduce((a, b) -> a + "|" + b)
          .orElse("");
      var digest = MessageDigest.getInstance("SHA-256")
          .digest(canonical.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (Exception e) {
      throw new IllegalStateException("hash failed", e);
    }
  }
}
```

`order/OrderService.java`:

```java
package com.sample.inventory.order;

import com.sample.inventory.common.error.IdempotencyConflictException;
import com.sample.inventory.common.error.InsufficientStockException;
import com.sample.inventory.common.error.NotFoundException;
import com.sample.inventory.inventory.InventoryRepository;
import com.sample.inventory.movement.MovementType;
import com.sample.inventory.movement.MovementWriter;
import com.sample.inventory.product.Product;
import com.sample.inventory.product.ProductRepository;
import java.time.Clock;
import java.util.Comparator;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

  private final SalesOrderRepository orderRepo;
  private final OrderIdempotencyRepository idemRepo;
  private final InventoryRepository invRepo;
  private final ProductRepository productRepo;
  private final MovementWriter movements;
  private final ReservationProperties props;
  private final Clock clock;

  @Transactional
  public OrderResponse create(CreateOrderRequest req, String idemKey, String reqHash) {
    if (idemKey != null) {
      var existing = idemRepo.findById(idemKey);
      if (existing.isPresent()) {
        if (!existing.get().getRequestHash().equals(reqHash)) {
          throw new IdempotencyConflictException();
        }
        return get(existing.get().getOrder().getId());
      }
    }
    var order = new SalesOrder();
    var sorted = req.lines().stream()
        .sorted(Comparator.comparing(CreateOrderRequest.CreateOrderLine::productId))
        .toList();
    record Pending(Allocation allocation, Reservation reservation) {}
    var pending = new java.util.ArrayList<Pending>();
    for (var line : sorted) {
      Product product = productRepo.findById(line.productId())
          .orElseThrow(() -> new NotFoundException("product", line.productId()));
      var ol = new OrderLine(order, product, line.qty());
      order.addLine(ol);
      int rest = line.qty();
      for (var inv : invRepo.lockAvailable(product.getId())) {
        if (rest == 0) {
          break;
        }
        int take = Math.min(rest, inv.getAvailable());
        inv.reserve(take);
        var allocation = new Allocation(ol, inv.getWarehouse(), take);
        ol.addAllocation(allocation);
        pending.add(new Pending(allocation,
            new Reservation(allocation, take, clock.instant().plus(props.ttl()))));
        rest -= take;
      }
      if (rest > 0) {
        throw new InsufficientStockException(product.getSku());
      }
    }
    orderRepo.saveAndFlush(order);
    for (var p : pending) {
      reservationRepo.save(p.reservation());
      movements.write(p.allocation().getOrderLine().getProduct(), p.allocation().getWarehouse(),
          MovementType.RESERVE, p.allocation().getQty(), "ORDER", order.getId());
    }
    if (idemKey != null) {
      try {
        idemRepo.saveAndFlush(new OrderIdempotency(idemKey, reqHash, order));
      } catch (DataIntegrityViolationException e) {
        var existing = idemRepo.findById(idemKey).orElseThrow(() -> e);
        if (!existing.getRequestHash().equals(reqHash)) {
          throw new IdempotencyConflictException();
        }
        return get(existing.getOrder().getId());
      }
    }
    return get(order.getId());
  }

  public OrderResponse get(long id) {
    return orderRepo.findDetailedById(id).map(OrderMapper::toResponse)
        .orElseThrow(() -> new NotFoundException("order", id));
  }

  public Page<OrderResponse> search(OrderStatus status, java.time.Instant from,
      java.time.Instant to, Pageable pageable) {
    return orderRepo.search(status, from, to, pageable).map(OrderMapper::toResponse);
  }
```

Fields needed: add `private final ReservationRepository reservationRepo;`. `get()` uses `findDetailedById` (EntityGraph — no lazy issues). Note: `create` returns `get(order.getId())` → second select with graph; fine.

`DuplicateException` import unused in service — REMOVE it (no import). Imports: IdempotencyConflictException, InsufficientStockException, NotFoundException, InventoryRepository, MovementType, MovementWriter, Product, ProductRepository, Clock, Comparator, ArrayList? (use `var pending = new ArrayList<Pending>()` with `import java.util.ArrayList;`), DataIntegrityViolationException, Page, Pageable, Instant.

`order/OrderController.java`:

```java
package com.sample.inventory.order;

import com.sample.inventory.common.error.DomainException;
import com.sample.inventory.common.error.ErrorCode;
import com.sample.inventory.common.web.ApiResponse;
import com.sample.inventory.common.web.PagedResult;
import com.sample.inventory.common.web.SortValidator;
import jakarta.validation.Valid;
import java.time.Instant;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

  private static final Set<String> SORTABLE = Set.of("createdAt");
  private static final Sort DEFAULT_SORT = Sort.by("createdAt").descending();

  private final OrderService service;

  @PostMapping
  public ResponseEntity<ApiResponse<OrderResponse>> create(
      @Valid @RequestBody CreateOrderRequest req,
      @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
    var res = service.create(req, idemKey, idemKey == null ? null : RequestHash.of(req));
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(res));
  }

  @GetMapping("/{id}")
  public ResponseEntity<ApiResponse<OrderResponse>> get(@PathVariable long id) {
    return ResponseEntity.ok(ApiResponse.ok(service.get(id)));
  }

  @GetMapping
  public ResponseEntity<ApiResponse<PagedResult<OrderResponse>>> search(
      @RequestParam(required = false) OrderStatus status,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      Pageable pageable) {
    if (from != null && to != null && from.isAfter(to)) {
      throw new DomainException(ErrorCode.VALIDATION);
    }
    var page = service.search(status, from, to,
        SortValidator.validated(pageable, SORTABLE, DEFAULT_SORT));
    return ResponseEntity.ok(ApiResponse.ok(PagedResult.from(page)));
  }
}
```

Confirm/cancel endpoints come in Task 6 — NOT here.

- [ ] **Step 3: Run GREEN**

Run: `./mvnw -q -Dtest=OrderCreateIT test` (JDK 21)
Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/sample/inventory/order src/test/java/com/sample/inventory/order/OrderCreateIT.java
git commit -m "feat: add order creation with split allocation and idempotency"
```

### Task 6: Confirm + cancel + scheduling enable

**Files:**
- Modify: `InventoryOrderManagementSystemApplication.java` (add `@EnableScheduling` + `@ConfigurationPropertiesScan`), `order/OrderService.java` (add confirm/cancel)
- Modify: `order/OrderController.java` (add endpoints)
- Create test: `order/OrderConfirmCancelIT.java`

**Interfaces:**
- Consumes: `InventoryRepository.lockOne`, `findDetailedById`, reservation guards
- Produces: `OrderService.confirm/cancel`, `POST /orders/{id}/confirm|/cancel`

- [ ] **Step 1: Write failing IT first**

`src/test/java/com/sample/inventory/order/OrderConfirmCancelIT.java`:

```java
package com.sample.inventory.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sample.inventory.TestcontainersConfiguration;
import com.sample.inventory.common.error.InvalidTransitionException;
import com.sample.inventory.inventory.Inventory;
import com.sample.inventory.inventory.InventoryRepository;
import com.sample.inventory.movement.MovementType;
import com.sample.inventory.movement.StockMovementRepository;
import com.sample.inventory.order.CreateOrderRequest.CreateOrderLine;
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
class OrderConfirmCancelIT {

  @Autowired OrderService orders;
  @Autowired SalesOrderRepository orderRepo;
  @Autowired ReservationRepository reservationRepo;
  @Autowired InventoryRepository invRepo;
  @Autowired ProductRepository productRepo;
  @Autowired WarehouseRepository warehouseRepo;
  @Autowired StockMovementRepository movementRepo;

  Product p;
  Warehouse w;

  @BeforeEach
  void seed() {
    movementRepo.deleteAll();
    reservationRepo.deleteAll();
    orderRepo.deleteAll();
    invRepo.deleteAll();
    p = productRepo.save(new Product("SKU-" + System.nanoTime(), "Keyboard"));
    w = warehouseRepo.save(new Warehouse("W-" + System.nanoTime(), "W", 1));
    invRepo.save(new Inventory(p, w, 5, 0, 1));
  }

  private OrderResponse pendingOrder(int qty) {
    return orders.create(new CreateOrderRequest(List.of(new CreateOrderLine(p.getId(), qty))), null, null);
  }

  @Test
  void confirmDecrementsReservedAndWritesOut() {
    var created = pendingOrder(2);
    var confirmed = orders.confirm(created.id());
    assertThat(confirmed.status()).isEqualTo(OrderStatus.CONFIRMED);
    var inv = invRepo.lockOne(p.getId(), w.getId()).orElseThrow();
    assertThat(inv.getAvailable()).isEqualTo(3);
    assertThat(inv.getReserved()).isEqualTo(0);
    assertThat(movementRepo.findAll()).extracting(m -> m.getType())
        .containsExactlyInAnyOrder(MovementType.RESERVE, MovementType.OUT);
  }

  @Test
  void doubleConfirmIsIdempotent() {
    var created = pendingOrder(2);
    var first = orders.confirm(created.id());
    var second = orders.confirm(created.id());
    assertThat(second.status()).isEqualTo(OrderStatus.CONFIRMED);
    assertThat(second.id()).isEqualTo(first.id());
    assertThat(movementRepo.count()).isEqualTo(2);
  }

  @Test
  void cancelRestoresStock() {
    var created = pendingOrder(2);
    var cancelled = orders.cancel(created.id());
    assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
    var inv = invRepo.lockOne(p.getId(), w.getId()).orElseThrow();
    assertThat(inv.getAvailable()).isEqualTo(5);
    assertThat(inv.getReserved()).isEqualTo(0);
    assertThat(movementRepo.findAll()).extracting(m -> m.getType())
        .containsExactlyInAnyOrder(MovementType.RESERVE, MovementType.RELEASE);
  }

  @Test
  void confirmAfterCancelIsInvalid() {
    var created = pendingOrder(2);
    orders.cancel(created.id());
    assertThatThrownBy(() -> orders.confirm(created.id()))
        .isInstanceOf(InvalidTransitionException.class);
  }
}
```

Note on `deleteAll` order: movement → reservation → order (cascades lines+allocations) → inventory. Product/warehouse rows accumulate per test (unique codes via nanoTime) — acceptable.

Run: `./mvnw -q -Dtest=OrderConfirmCancelIT test`
Expected: FAIL — compilation error (`confirm`/`cancel` do not exist).

- [ ] **Step 2: Implement (exact)**

In `InventoryOrderManagementSystemApplication.java` add annotations (read file first, preserve rest):

```java
@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication
public class InventoryOrderManagementSystemApplication {
```

imports: `org.springframework.boot.context.properties.ConfigurationPropertiesScan`, `org.springframework.scheduling.annotation.EnableScheduling`.

Append to `OrderService` (new methods; add `lockOne` usage):

```java
  @Transactional
  public OrderResponse confirm(long id) {
    var order = orderRepo.findDetailedById(id)
        .orElseThrow(() -> new NotFoundException("order", id));
    if (order.getStatus() == OrderStatus.CONFIRMED) {
      return OrderMapper.toResponse(order);
    }
    order.confirm();
    for (var line : order.getLines()) {
      for (var alloc : line.getAllocations()) {
        var inv = invRepo.lockOne(line.getProduct().getId(), alloc.getWarehouse().getId())
            .orElseThrow(() -> new NotFoundException("inventory",
                line.getProduct().getId() + "/" + alloc.getWarehouse().getId()));
        inv.confirm(alloc.getQty());
        alloc.getReservation().confirm();
        movements.write(line.getProduct(), alloc.getWarehouse(), MovementType.OUT,
            alloc.getQty(), "ORDER", order.getId());
      }
    }
    return OrderMapper.toResponse(order);
  }

  @Transactional
  public OrderResponse cancel(long id) {
    var order = orderRepo.findDetailedById(id)
        .orElseThrow(() -> new NotFoundException("order", id));
    order.cancel();
    for (var line : order.getLines()) {
      for (var alloc : line.getAllocations()) {
        var inv = invRepo.lockOne(line.getProduct().getId(), alloc.getWarehouse().getId())
            .orElseThrow(() -> new NotFoundException("inventory",
                line.getProduct().getId() + "/" + alloc.getWarehouse().getId()));
        inv.release(alloc.getQty());
        alloc.getReservation().cancel();
        movements.write(line.getProduct(), alloc.getWarehouse(), MovementType.RELEASE,
            alloc.getQty(), "ORDER", order.getId());
      }
    }
    return OrderMapper.toResponse(order);
  }
```

Note: `alloc.getReservation()` — Allocation→Reservation is `mappedBy` LAZY; entity graph in `findDetailedById` includes `lines.allocations.reservation` so it is initialized. Good.

Append to `OrderController`:

```java
  @PostMapping("/{id}/confirm")
  public ResponseEntity<ApiResponse<OrderResponse>> confirm(@PathVariable long id) {
    return ResponseEntity.ok(ApiResponse.ok(service.confirm(id)));
  }

  @PostMapping("/{id}/cancel")
  public ResponseEntity<ApiResponse<OrderResponse>> cancel(@PathVariable long id) {
    return ResponseEntity.ok(ApiResponse.ok(service.cancel(id)));
  }
```

- [ ] **Step 3: Run GREEN**

Run: `./mvnw -q -Dtest=OrderConfirmCancelIT test` (JDK 21)
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/sample/inventory/InventoryOrderManagementSystemApplication.java src/main/java/com/sample/inventory/order/OrderService.java src/main/java/com/sample/inventory/order/OrderController.java src/test/java/com/sample/inventory/order/OrderConfirmCancelIT.java
git commit -m "feat: add order confirm and cancel with idempotent confirm"
```

### Task 7: Expiry worker

**Files:**
- Create: `order/ReservationExpiryService.java`, `order/ReservationExpiryWorker.java`
- Create test: `order/ReservationExpiryIT.java`

**Interfaces:**
- Consumes: `ReservationRepository.lockDue`, `InventoryRepository.lockOne`, `MovementWriter`, `Clock`
- Produces: scheduled expiry (60s default); `expireBatch(Instant)` directly testable

- [ ] **Step 1: Write failing IT first**

`src/test/java/com/sample/inventory/order/ReservationExpiryIT.java`:

```java
package com.sample.inventory.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.sample.inventory.TestcontainersConfiguration;
import com.sample.inventory.inventory.Inventory;
import com.sample.inventory.inventory.InventoryRepository;
import com.sample.inventory.movement.MovementType;
import com.sample.inventory.movement.StockMovementRepository;
import com.sample.inventory.order.CreateOrderRequest.CreateOrderLine;
import com.sample.inventory.product.Product;
import com.sample.inventory.product.ProductRepository;
import com.sample.inventory.warehouse.Warehouse;
import com.sample.inventory.warehouse.WarehouseRepository;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "app.reservation.ttl=PT1S")
class ReservationExpiryIT {

  @Autowired OrderService orders;
  @Autowired ReservationExpiryService expiry;
  @Autowired SalesOrderRepository orderRepo;
  @Autowired ReservationRepository reservationRepo;
  @Autowired InventoryRepository invRepo;
  @Autowired ProductRepository productRepo;
  @Autowired WarehouseRepository warehouseRepo;
  @Autowired StockMovementRepository movementRepo;

  Product p;
  Warehouse w;

  @BeforeEach
  void seed() {
    movementRepo.deleteAll();
    reservationRepo.deleteAll();
    orderRepo.deleteAll();
    invRepo.deleteAll();
    p = productRepo.save(new Product("SKU-" + System.nanoTime(), "Keyboard"));
    w = warehouseRepo.save(new Warehouse("W-" + System.nanoTime(), "W", 1));
    invRepo.save(new Inventory(p, w, 5, 0, 1));
  }

  @Test
  void expiredReservationRestoresStock() throws Exception {
    var created = orders.create(
        new CreateOrderRequest(List.of(new CreateOrderLine(p.getId(), 2))), null, null);
    Thread.sleep(1200);
    int n = expiry.expireBatch(java.time.Instant.now());
    assertThat(n).isEqualTo(1);
    var inv = invRepo.lockOne(p.getId(), w.getId()).orElseThrow();
    assertThat(inv.getAvailable()).isEqualTo(5);
    assertThat(inv.getReserved()).isEqualTo(0);
    assertThat(reservationRepo.findAll().get(0).getStatus())
        .isEqualTo(ReservationStatus.EXPIRED);
    assertThat(movementRepo.findAll()).extracting(m -> m.getType())
        .containsExactlyInAnyOrder(MovementType.RESERVE, MovementType.RELEASE);
    assertThat(orders.get(created.id()).status()).isEqualTo(OrderStatus.PENDING);
  }

  @Test
  void freshReservationIsUntouched() {
    orders.create(new CreateOrderRequest(List.of(new CreateOrderLine(p.getId(), 2))), null, null);
    int n = expiry.expireBatch(java.time.Instant.now());
    assertThat(n).isZero();
  }
}
```

Note: order itself stays PENDING after expiry (spec: only reservation flips to EXPIRED; stock restored). Second test races TTL=1s: create then expire immediately — expires_at = now+1s > now so untouched. Tiny flake risk if the clock crosses a second boundary... expires_at is computed at create (T+1s); expireBatch runs milliseconds later with now < expires_at unless >1s elapsed. Safe.

`@TestPropertySource` overrides TTL to 1s for this context (separate context from other ITs — slower but isolated and correct).

Run: `./mvnw -q -Dtest=ReservationExpiryIT test`
Expected: FAIL — compilation error.

- [ ] **Step 2: Implement (exact)**

`order/ReservationExpiryService.java`:

```java
package com.sample.inventory.order;

import com.sample.inventory.common.error.NotFoundException;
import com.sample.inventory.inventory.InventoryRepository;
import com.sample.inventory.movement.MovementType;
import com.sample.inventory.movement.MovementWriter;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReservationExpiryService {

  private final ReservationRepository reservationRepo;
  private final InventoryRepository invRepo;
  private final MovementWriter movements;

  @Transactional
  public int expireBatch(Instant now) {
    var due = reservationRepo.lockDue(now, 500);
    for (var r : due) {
      var alloc = r.getAllocation();
      var line = alloc.getOrderLine();
      var inv = invRepo.lockOne(line.getProduct().getId(), alloc.getWarehouse().getId())
          .orElseThrow(() -> new NotFoundException("inventory",
              line.getProduct().getId() + "/" + alloc.getWarehouse().getId()));
      inv.release(r.getQty());
      r.expire();
      movements.write(line.getProduct(), alloc.getWarehouse(), MovementType.RELEASE,
          r.getQty(), "ORDER", line.getOrder().getId());
    }
    return due.size();
  }
}
```

Lazy loading check: `lockDue` native query returns managed Reservation entities; `r.getAllocation()` LAZY → loaded within tx (method is @Transactional). `alloc.getOrderLine()`, `line.getProduct()`, `line.getOrder()` — all lazy but inside tx. OK.

`order/ReservationExpiryWorker.java`:

```java
package com.sample.inventory.order;

import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReservationExpiryWorker {

  private final ReservationExpiryService expiry;
  private final Clock clock;

  @Scheduled(fixedDelayString = "${app.reservation.expire-interval:PT60S}")
  public void run() {
    expiry.expireBatch(clock.instant());
  }
}
```

- [ ] **Step 3: Run GREEN**

Run: `./mvnw -q -Dtest=ReservationExpiryIT test` (JDK 21)
Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/sample/inventory/order/ReservationExpiryService.java src/main/java/com/sample/inventory/order/ReservationExpiryWorker.java src/test/java/com/sample/inventory/order/ReservationExpiryIT.java
git commit -m "feat: add reservation expiry worker"
```

### Task 8: Concurrency proof + verify + demo

**Files:**
- Create test: `order/OrderConcurrencyIT.java`
- Modify: `README.md` (order API rows)

**Interfaces:**
- Consumes: everything (final gate of Fase 1b)
- Produces: race proof (exactly 1 winner), full `verify` green, live demo

- [ ] **Step 1: Write the race test first (exact)**

`src/test/java/com/sample/inventory/order/OrderConcurrencyIT.java`:

```java
package com.sample.inventory.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.sample.inventory.TestcontainersConfiguration;
import com.sample.inventory.inventory.Inventory;
import com.sample.inventory.inventory.InventoryRepository;
import com.sample.inventory.movement.StockMovementRepository;
import com.sample.inventory.order.CreateOrderRequest.CreateOrderLine;
import com.sample.inventory.product.Product;
import com.sample.inventory.product.ProductRepository;
import com.sample.inventory.warehouse.Warehouse;
import com.sample.inventory.warehouse.WarehouseRepository;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OrderConcurrencyIT {

  @Autowired OrderService orders;
  @Autowired SalesOrderRepository orderRepo;
  @Autowired ReservationRepository reservationRepo;
  @Autowired InventoryRepository invRepo;
  @Autowired ProductRepository productRepo;
  @Autowired WarehouseRepository warehouseRepo;
  @Autowired StockMovementRepository movementRepo;

  Product p;
  Warehouse w;

  @BeforeEach
  void seed() {
    movementRepo.deleteAll();
    reservationRepo.deleteAll();
    orderRepo.deleteAll();
    invRepo.deleteAll();
    p = productRepo.save(new Product("SKU-" + System.nanoTime(), "Keyboard"));
    w = warehouseRepo.save(new Warehouse("W-" + System.nanoTime(), "W", 1));
    invRepo.save(new Inventory(p, w, 1, 0, 1));
  }

  @Test
  void lastUnitHasExactlyOneWinner() throws Exception {
    var gate = new CountDownLatch(1);
    var done = new CountDownLatch(2);
    var wins = new AtomicInteger();
    var fails = new AtomicInteger();
    try (var pool = Executors.newFixedThreadPool(2)) {
      List<Future<?>> futures = List.of(pool.submit(() -> attempt(gate, done, wins, fails)),
          pool.submit(() -> attempt(gate, done, wins, fails)));
      gate.countDown();
      done.await();
      for (var f : futures) {
        f.get();
      }
    }
    assertThat(wins.get()).isEqualTo(1);
    assertThat(fails.get()).isEqualTo(1);
    var inv = invRepo.lockAvailable(p.getId());
    assertThat(inv).hasSize(1);
    assertThat(inv.get(0).getAvailable()).isZero();
    assertThat(inv.get(0).getReserved()).isEqualTo(1);
    assertThat(movementRepo.count()).isEqualTo(1);
  }

  private void attempt(CountDownLatch gate, CountDownLatch done,
      AtomicInteger wins, AtomicInteger fails) {
    try {
      gate.await();
      orders.create(new CreateOrderRequest(List.of(new CreateOrderLine(p.getId(), 1))), null, null);
      wins.incrementAndGet();
    } catch (Exception e) {
      fails.incrementAndGet();
    } finally {
      done.countDown();
    }
  }
}
```

Note: loser may fail with `InsufficientStockException` (sees 0 after winner commits) or `STOCK_CONTENTION` lock timeout (blocks 3s then... actually with only 1 row and 3s timeout, loser usually acquires lock after winner commits, then sees available=0 → INSUFFICIENT). Either way `fails=1`. Test asserts counts only — robust to both paths.

Run: `./mvnw -q -Dtest=OrderConcurrencyIT test`
Expected: FAIL — compilation error? NO — all classes exist by now! This test compiles immediately. RED here means: run it to see it PASS against finished code (no TDD red possible for the last integration). So: run once to confirm it passes (it exercises Task 5 code paths concurrently). If it fails/flakes, report BLOCKED with output — do not modify production code in this task; fixes go back to Task 5 via controller.

- [ ] **Step 2: Full verify + format**

Run: `./mvnw spotless:apply` then `./mvnw verify` (JDK 21)
Expected: BUILD SUCCESS, all tests green, `spotless:check` clean. If `spotless:apply` produces non-format content changes, stop and report NEEDS_CONTEXT.

- [ ] **Step 3: Live demo (dev profile, short TTL via env)**

1. Start: `$env:SPRING_PROFILES_ACTIVE='dev'; $env:APP_RESERVATION_TTL='PT30S'; ./mvnw spring-boot:run` (JDK 21), wait for Started
2. `curl -s -X POST http://localhost:8080/api/v1/orders -H 'Content-Type: application/json' -d '{"lines":[{"productId":<id>,"qty":1}]}'` → expect 201 with allocations (get `<id>` from `GET /api/v1/products?q=KB-100`)
3. `curl -s -X POST http://localhost:8080/api/v1/orders/<orderId>/confirm` → expect 200 CONFIRMED
4. `curl -s 'http://localhost:8080/api/v1/stock-movements'` → expect RESERVE + OUT entries
5. Stop app. Record outputs.

For step 2 the dev DB needs inventory rows — dev DB currently has only seed product/warehouses (no stock). Create stock via... no endpoint sets stock yet (receiving is Fase 2)! Demo workaround: `POST /orders` will 409 without stock. Options: (a) insert via psql in demo, (b) skip live order demo, only show endpoints. Plan: insert one row via psql:
`INSERT INTO inventory (product_id, warehouse_id, available, reserved, low_stock_threshold) VALUES (<pid>, <wid>, 10, 0, 2) ON CONFLICT (product_id, warehouse_id) DO UPDATE SET available = 10;`
Include this in demo steps (dev DB only, idempotent).

- [ ] **Step 4: README order rows + commit**

Append under `## API (Fase 1a)` — change heading to `## API` and append:

```markdown
| POST | /api/v1/orders (+ Idempotency-Key) | create split allocation (201), 409 if short |
| GET | /api/v1/orders?status=&from=&to= | paged, sort: createdAt |
| GET | /api/v1/orders/{id} | detail with lines + allocations |
| POST | /api/v1/orders/{id}/confirm | idempotent confirm (200) |
| POST | /api/v1/orders/{id}/cancel | cancel + restock (200) |
| GET | /api/v1/inventories?productId=&warehouseId=&lowStockOnly= | paged |
| GET | /api/v1/stock-movements?... | paged + type/date filters |
```

(Read README first; change the `## API (Fase 1a)` heading to `## API` in the same edit.)

```bash
git add src/test/java/com/sample/inventory/order/OrderConcurrencyIT.java README.md
git commit -m "test: prove single-winner concurrency and document order api"
```

## Plan Complete — Fase 2 Preview

Next plan (separate file): purchase order + receiving, returns to origin warehouse, low-stock Kafka alert wiring, transfers.
