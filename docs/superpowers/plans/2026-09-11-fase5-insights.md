# Fase 5 (Rule-Based Insights) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Five rule-based AI features (demand forecast, smart replenishment, anomaly detection, recommendations, assistant) as deterministic, testable read endpoints under `/api/v1/insights/*` — no models, no new dependencies, no migrations.

**Architecture:** One `insights/` feature package; each capability is a small read-only `@Service` over existing repositories (no new tables); a single `InsightsController` exposes all five endpoints; `AssistantProvider` interface + `RuleBasedAssistantProvider` keeps the LLM upgrade path open without changing contracts.

**Tech Stack:** Java 21, Spring Boot 4.1.1, PostgreSQL 16, Testcontainers, JUnit 5 + Mockito + AssertJ. No Kafka/Redis/broker needed (pure reads; existing suite patterns apply).

## Global Constraints

- Branch `phase-five` (from `main`); base package `com.sample.inventory`; API prefix `/api/v1/insights`.
- NO new migration, NO new dependency, NO API key, NO LLM call anywhere. If a step needs any of these, stop and report NEEDS_CONTEXT instead.
- JPA `ddl-auto: validate` — read-only queries only.
- Build JDK 21 (`$env:JAVA_HOME='C:\Program Files\Java\jdk-21'`); `mvn clean` on toolchain switch.
- DTOs as records; service→DTO, controller→ResponseEntity<ApiResponse<…>>; no entity leaks.
- `@Transactional(readOnly = true)` on services; `parallelStream` banned; no H2.
- Boot 4.1.1 test imports; Testcontainers 2.x; `@Import(TestcontainersConfiguration.class)` for ITs.
- Method names in responses are honest labels (`"moving-average-30d"`, `"co-occurrence"`, `"rule-based-v1"`) — never claim ML.
- Conventional Commits; `spotless:check` green; do NOT push.

---

## File Structure

```
src/main/java/com/sample/inventory/insights/
  InsightsController.java
  forecast/DemandForecastService.java, forecast/DemandForecastResponse.java
  replenishment/ReplenishmentProperties.java, replenishment/ReplenishmentService.java,
    replenishment/ReplenishmentSuggestion.java
  anomaly/AnomalyService.java, anomaly/AnomalyDto.java, anomaly/AnomalyType.java
  recommendation/RecommendationService.java, recommendation/RecommendationDto.java
  assistant/AssistantProvider.java, assistant/RuleBasedAssistantProvider.java,
    assistant/AssistantRequest.java, assistant/AssistantResponse.java
src/test/.../insights/ForecastIT.java, ReplenishmentIT.java, AnomalyIT.java,
  RecommendationIT.java, AssistantTest.java (Mockito unit)
```

### Task 1: Demand forecasting

**Files:**
- Create: `insights/forecast/DemandForecastService.java`, `insights/forecast/DemandForecastResponse.java`, `order/OrderLineRepository.java`
- Create test: `insights/ForecastIT.java`

**Interfaces:**
- Consumes: `ProductRepository`, `OrderService` (seed confirmed history in tests)
- Produces: `OrderLineRepository.findConfirmedByProductSince`; `DemandForecastService.forecast(productId, horizonDays)` returning `DemandForecastResponse` — consumed by Task 2
```java
package com.sample.inventory.order;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OrderLineRepository extends JpaRepository<OrderLine, Long> {

  @EntityGraph(attributePaths = {"order"})
  @Query("select l from OrderLine l where l.product.id = :productId"
      + " and l.order.status = :status and l.order.createdAt >= :since"
      + " order by l.order.createdAt asc")
  List<OrderLine> findConfirmedByProductSince(long productId, OrderStatus status, Instant since);
}
```

- [ ] **Step 1: Write IT first (exact)**

`src/test/java/com/sample/inventory/insights/ForecastIT.java`:
```java
package com.sample.inventory.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.sample.inventory.TestcontainersConfiguration;
import com.sample.inventory.common.error.NotFoundException;
import com.sample.inventory.insights.forecast.DemandForecastService;
import com.sample.inventory.order.OrderLineRepository;
import com.sample.inventory.order.OrderService;
import com.sample.inventory.order.CreateOrderRequest;
import com.sample.inventory.order.CreateOrderRequest.CreateOrderLine;
import com.sample.inventory.order.SalesOrderRepository;
import com.sample.inventory.inventory.Inventory;
import com.sample.inventory.inventory.InventoryRepository;
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
class ForecastIT {

  @Autowired DemandForecastService forecast;
  @Autowired OrderService orders;
  @Autowired SalesOrderRepository orderRepo;
  @Autowired InventoryRepository invRepo;
  @Autowired ProductRepository productRepo;
  @Autowired WarehouseRepository warehouseRepo;
  @Autowired StockMovementRepository movementRepo;

  Product p;

  @BeforeEach
  void seed() {
    movementRepo.deleteAll();
    orderRepo.deleteAll();
    invRepo.deleteAll();
    p = productRepo.save(new Product("SKU-" + System.nanoTime(), "Keyboard"));
    var w = warehouseRepo.save(new Warehouse("W-" + System.nanoTime(), "W", 1));
    invRepo.save(new Inventory(p, w, 100, 0, 1));
    var order = orders.create(
        new CreateOrderRequest(List.of(new CreateOrderLine(p.getId(), 10))), null, null);
    orders.confirm(order.id());
  }

  @Test
  void forecastsFromConfirmedHistory() {
    var res = forecast.forecast(p.getId(), 30);
    assertThat(res.productSku()).isEqualTo(p.getSku());
    assertThat(res.avgDailyQty()).isCloseTo(10.0 / 30.0, within(0.000001));
    assertThat(res.forecastedQty()).isCloseTo(10.0, within(0.0001));
    assertThat(res.method()).isEqualTo("moving-average-30d");
  }

  @Test
  void unknownProductThrowsNotFound() {
    assertThatThrownBy(() -> forecast.forecast(999999L, 30))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void badHorizonRejected() {
    assertThat(forecast.forecast(p.getId(), 1).forecastedQty())
        .isCloseTo(10.0 / 30.0, within(0.000001));
  }
}
```
Golden math: 10 units in 30d window → avg≈0.333, horizon 30 → ≈10.0 (asserted with tolerance).

Run: `./mvnw -q -Dtest=ForecastIT test`
Expected RED: compilation error (service/DTO/repo do not exist).

- [ ] **Step 2: Implement (exact)**

`order/OrderLineRepository.java` (new file, exact as in Interfaces above).

`insights/forecast/DemandForecastResponse.java`:
```java
package com.sample.inventory.insights.forecast;

public record DemandForecastResponse(Long productId, String productSku, int windowDays,
    double avgDailyQty, int horizonDays, double forecastedQty, String method) {}
```

`insights/forecast/DemandForecastService.java`:
```java
package com.sample.inventory.insights.forecast;

import com.sample.inventory.common.error.NotFoundException;
import com.sample.inventory.order.OrderLineRepository;
import com.sample.inventory.order.OrderStatus;
import com.sample.inventory.product.ProductRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DemandForecastService {

  private static final int WINDOW_DAYS = 30;

  private final OrderLineRepository lineRepo;
  private final ProductRepository productRepo;
  private final Clock clock;

  public DemandForecastResponse forecast(long productId, int horizonDays) {
    var product = productRepo.findById(productId)
        .orElseThrow(() -> new NotFoundException("product", productId));
    int horizon = Math.min(Math.max(horizonDays, 1), 90);
    var since = clock.instant().minusSeconds((long) WINDOW_DAYS * 24 * 3600);
    var lines = lineRepo.findConfirmedByProductSince(productId, OrderStatus.CONFIRMED, since);
    var perDay = new HashMap<LocalDate, Integer>();
    for (var l : lines) {
      var day = l.getOrder().getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
      perDay.merge(day, l.getQty(), Integer::sum);
    }
    int sum = perDay.values().stream().mapToInt(Integer::intValue).sum();
    double avg = (double) sum / WINDOW_DAYS;
    return new DemandForecastResponse(productId, product.getSku(), WINDOW_DAYS,
        avg, horizon, avg * horizon, "moving-average-30d");
  }
}
```
Calendar-day average (zeros included) — conservative, documented in `method` label.

- [ ] **Step 3: GREEN**

Run: `./mvnw -q -Dtest=ForecastIT test` (JDK 21)
Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/sample/inventory/order/OrderLineRepository.java src/main/java/com/sample/inventory/insights/forecast src/test/java/com/sample/inventory/insights/ForecastIT.java
git commit -m "feat: add demand forecasting with moving average"
```

### Task 2: Smart replenishment

**Files:**
- Create: `insights/replenishment/ReplenishmentProperties.java`, `insights/replenishment/ReplenishmentService.java`, `insights/replenishment/ReplenishmentSuggestion.java`
- Create test: `insights/ReplenishmentIT.java`

**Interfaces:**
- Consumes: Task 1 `DemandForecastService.forecast`; `InventoryRepository` (sum per product — add `sumAvailableByProduct`? Simpler: reuse `findByProductId` list and sum in Java — no new query); `ProductRepository.findAll` (all active products)
- Produces: `ReplenishmentService.suggestAll()` — consumed by Task 5 assistant

- [ ] **Step 1: Write IT first (exact)**

`src/test/java/com/sample/inventory/insights/ReplenishmentIT.java`:
```java
package com.sample.inventory.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.sample.inventory.TestcontainersConfiguration;
import com.sample.inventory.insights.replenishment.ReplenishmentService;
import com.sample.inventory.inventory.Inventory;
import com.sample.inventory.inventory.InventoryRepository;
import com.sample.inventory.movement.StockMovementRepository;
import com.sample.inventory.order.CreateOrderRequest;
import com.sample.inventory.order.CreateOrderRequest.CreateOrderLine;
import com.sample.inventory.order.OrderService;
import com.sample.inventory.order.SalesOrderRepository;
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
class ReplenishmentIT {

  @Autowired ReplenishmentService replenishment;
  @Autowired OrderService orders;
  @Autowired SalesOrderRepository orderRepo;
  @Autowired InventoryRepository invRepo;
  @Autowired ProductRepository productRepo;
  @Autowired WarehouseRepository warehouseRepo;
  @Autowired StockMovementRepository movementRepo;

  Product p;

  @BeforeEach
  void seed() {
    movementRepo.deleteAll();
    orderRepo.deleteAll();
    invRepo.deleteAll();
    p = productRepo.save(new Product("SKU-" + System.nanoTime(), "Keyboard"));
    var w = warehouseRepo.save(new Warehouse("W-" + System.nanoTime(), "W", 1));
    invRepo.save(new Inventory(p, w, 12, 0, 5));
    var order = orders.create(
        new CreateOrderRequest(List.of(new CreateOrderLine(p.getId(), 10))), null, null);
    orders.confirm(order.id());
  }

  @Test
  void suggestsBelowReorderPoint() {
    // avg=10/30 per day; lead 7d + safety 3d → reorderPoint=10.0; available=12-10=2 → suggest 8
    var all = replenishment.suggestAll();
    var mine = all.stream().filter(s -> s.productSku().equals(p.getSku())).toList();
    assertThat(mine).hasSize(1);
    var s = mine.get(0);
    assertThat(s.available()).isEqualTo(2);
    assertThat(s.reorderPoint()).isCloseTo(10.0, within(0.0001));
    assertThat(s.suggestedQty()).isEqualTo(8);
    assertThat(s.reason()).contains("lead");
  }

  @Test
  void healthyStockProducesNothing() {
    var q = productRepo.save(new Product("SKU2-" + System.nanoTime(), "Mouse"));
    var w2 = warehouseRepo.save(new Warehouse("W2-" + System.nanoTime(), "W2", 2));
    invRepo.save(new Inventory(q, w2, 50, 0, 1));
    var all = replenishment.suggestAll();
    assertThat(all.stream().filter(s -> s.productSku().equals(q.getSku())).toList()).isEmpty();
  }
}
```
Filtering by own SKU (not exact sizes) keeps the tests robust against leftover rows from other test classes sharing the container DB. Needs `import static org.assertj.core.api.Assertions.within;`.

Run: `./mvnw -q -Dtest=ReplenishmentIT test`
Expected RED: compilation error.

- [ ] **Step 2: Implement (exact)**

`insights/replenishment/ReplenishmentProperties.java`:
```java
package com.sample.inventory.insights.replenishment;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.replenishment")
public record ReplenishmentProperties(int leadTimeDays, int safetyDays) {
}
```
Records carry no defaults, so both yml files must provide the keys (binding fails otherwise). Add to `src/main/resources/application.yml` (merged under existing top-level `app:` — READ file first):
```yaml
app:
  replenishment:
    lead-time-days: 7
    safety-days: 3
```
And the same block to `src/test/resources/application.yml` (test shadowing proven in Fase 3 — READ first). BOTH yml files go in this task's commit. Registration: existing `@ConfigurationPropertiesScan` covers `com.sample.inventory.insights.replenishment` ✓.

`insights/replenishment/ReplenishmentSuggestion.java`:
```java
package com.sample.inventory.insights.replenishment;

public record ReplenishmentSuggestion(Long productId, String productSku, int available,
    double reorderPoint, int suggestedQty, String reason) {}
```

`insights/replenishment/ReplenishmentService.java`:
```java
package com.sample.inventory.insights.replenishment;

import com.sample.inventory.insights.forecast.DemandForecastService;
import com.sample.inventory.inventory.InventoryRepository;
import com.sample.inventory.product.ProductRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReplenishmentService {

  private final DemandForecastService forecast;
  private final InventoryRepository invRepo;
  private final ProductRepository productRepo;
  private final ReplenishmentProperties props;

  public List<ReplenishmentSuggestion> suggestAll() {
    var out = new ArrayList<ReplenishmentSuggestion>();
    for (var product : productRepo.findAll()) {
      if (!product.isActive()) {
        continue;
      }
      var fc = forecast.forecast(product.getId(), 30);
      double reorderPoint = fc.avgDailyQty() * props.leadTimeDays()
          + fc.avgDailyQty() * props.safetyDays();
      int available = invRepo.findByProductId(product.getId()).stream()
          .mapToInt(i -> i.getAvailable()).sum();
      if (available < reorderPoint) {
        int suggested = (int) Math.ceil(reorderPoint - available);
        out.add(new ReplenishmentSuggestion(product.getId(), product.getSku(), available,
            reorderPoint, suggested,
            "available " + available + " below reorder point " + reorderPoint
                + " (lead " + props.leadTimeDays() + "d + safety " + props.safetyDays() + "d)"));
      }
    }
    out.sort((a, b) -> Double.compare(
        a.reorderPoint() - a.available(), b.reorderPoint() - b.available()) * -1);
    return out;
  }
}
```
Math check: avg=1.0, reorderPoint=10.0, available=2 → suggested=ceil(8.0)=8 ✓. Reason contains "lead" ✓ ("(lead 7d + safety 3d)"). Sort: urgency desc (gap desc) — comparator: `Double.compare(gapA, gapB) * -1` = descending ✓.
`findByProductId` — EXISTS? Fase 3 Task 3 added it to InventoryRepository ✓ (with EntityGraph). Confirm import-free (same... no, different package `insights.replenishment` vs `inventory` — service uses repo BEAN, no entity import needed except... `i.getAvailable()` on Inventory type inferred via var/stream — NO import needed (type inference). ✓ `Product` type in for-loop: `for (var product : ...)` — var, no import ✓.
`product.isActive()` — Product has `isActive()`? Entity has `boolean active` + `@Getter` → `isActive()` ✓.

- [ ] **Step 3: GREEN**

Run: `./mvnw -q -Dtest=ReplenishmentIT test` (JDK 21)
Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/sample/inventory/insights/replenishment src/test/java/com/sample/inventory/insights/ReplenishmentIT.java src/main/resources/application.yml src/test/resources/application.yml
git commit -m "feat: add smart replenishment suggestions"
```

### Task 3: Anomaly detection

**Files:**
- Create: `insights/anomaly/AnomalyType.java`, `insights/anomaly/AnomalyDto.java`, `insights/anomaly/AnomalyService.java`
- Create test: `insights/AnomalyIT.java`

**Interfaces:**
- Consumes: `StockMovementRepository` (+ movement types), `ReservationRepository` (add `countByStatus`? For global expiry ratio — new derived method `long countByStatus(ReservationStatus status)` — ADD to existing repo file, include in commit)
- Produces: `AnomalyService.scan(int days)` — consumed by Task 5 assistant (optional: include anomalies summary? NO — keep assistant scope as planned; anomaly stands alone)

Rules (constants in service, documented):
- SPIKE: single OUT movement qty > 3 × product's avg OUT qty (needs ≥3 prior OUT samples), severity HIGH if > 5× else MEDIUM.
- HIGH_RETURNS: per product, RETURN IN count ≥ 3 AND returns/(returns+OUT) > 0.5 → HIGH.
- HIGH_EXPIRY: global expired/(expired+confirmed) > 0.5 with total ≥ 5 → MEDIUM, productId null.
`detectedAt` = `clock.instant()` (inject Clock — exists as bean ✓).

- [ ] **Step 1: Write IT first (exact)**

`src/test/java/com/sample/inventory/insights/AnomalyIT.java`:
```java
package com.sample.inventory.insights;

import static org.assertj.core.api.Assertions.assertThat;

import com.sample.inventory.TestcontainersConfiguration;
import com.sample.inventory.insights.anomaly.AnomalyService;
import com.sample.inventory.insights.anomaly.AnomalyType;
import com.sample.inventory.inventory.Inventory;
import com.sample.inventory.inventory.InventoryRepository;
import com.sample.inventory.movement.MovementType;
import com.sample.inventory.movement.StockMovement;
import com.sample.inventory.movement.StockMovementRepository;
import com.sample.inventory.order.SalesOrderRepository;
import com.sample.inventory.product.Product;
import com.sample.inventory.product.ProductRepository;
import com.sample.inventory.warehouse.Warehouse;
import com.sample.inventory.warehouse.WarehouseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AnomalyIT {

  @Autowired AnomalyService anomalies;
  @Autowired StockMovementRepository movementRepo;
  @Autowired SalesOrderRepository orderRepo;
  @Autowired InventoryRepository invRepo;
  @Autowired ProductRepository productRepo;
  @Autowired WarehouseRepository warehouseRepo;

  Product p;
  Warehouse w;

  @BeforeEach
  void seed() {
    movementRepo.deleteAll();
    orderRepo.deleteAll();
    invRepo.deleteAll();
    p = productRepo.save(new Product("SKU-" + System.nanoTime(), "Keyboard"));
    w = warehouseRepo.save(new Warehouse("W-" + System.nanoTime(), "W", 1));
    invRepo.save(new Inventory(p, w, 100, 0, 1));
  }

  @Test
  void detectsConsumptionSpike() {
    for (int i = 0; i < 3; i++) {
      movementRepo.save(StockMovement.of(p, w, MovementType.OUT, 2, "ORDER", 100 + i));
    }
    movementRepo.save(StockMovement.of(p, w, MovementType.OUT, 20, "ORDER", 200));
    var found = anomalies.scan(30);
    assertThat(found).filteredOn(a -> a.type() == AnomalyType.SPIKE
        && a.productId().equals(p.getId())).hasSize(1);
  }

  @Test
  void quietDataProducesNothing() {
    movementRepo.save(StockMovement.of(p, w, MovementType.OUT, 2, "ORDER", 1));
    var found = anomalies.scan(30);
    assertThat(found).filteredOn(a -> a.productId() != null && a.productId().equals(p.getId()))
        .isEmpty();
  }
}
```
Spike math: prior avg = 2 (3 samples ≥ 3 ✓), 20 > 3×2=6 ✓, 20 > 5×2=10 → HIGH. Quiet: single OUT, <3 samples → nothing. HIGH_EXPIRY global: other tests' reservations may exist in shared container (expired/confirmed rows from OTHER classes!) → global ratio could false-positive in EITHER test!! Guard: filter by productId (expiry anomaly has productId null) — spike test filters type+product ✓ ignores global; quiet test filters productId!=null ✓ ignores global. SAFE by construction. 

`scan(int days)` — days param validated 1..90? Service clamps like forecast (no exception path to test). Fine.

Run: `./mvnw -q -Dtest=AnomalyIT test`
Expected RED: compilation error.

- [ ] **Step 2: Implement (exact)**

`insights/anomaly/AnomalyType.java`:
```java
package com.sample.inventory.insights.anomaly;

public enum AnomalyType {
  SPIKE,
  HIGH_RETURNS,
  HIGH_EXPIRY
}
```

`insights/anomaly/AnomalyDto.java`:
```java
package com.sample.inventory.insights.anomaly;

import java.time.Instant;

public record AnomalyDto(AnomalyType type, Long productId, String severity,
    String detail, Instant detectedAt) {}
```

`insights/anomaly/AnomalyService.java`:
```java
package com.sample.inventory.insights.anomaly;

import com.sample.inventory.movement.MovementType;
import com.sample.inventory.movement.StockMovement;
import com.sample.inventory.movement.StockMovementRepository;
import com.sample.inventory.order.ReservationRepository;
import com.sample.inventory.order.ReservationStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnomalyService {

  private final StockMovementRepository movementRepo;
  private final ReservationRepository reservationRepo;
  private final Clock clock;

  public List<AnomalyDto> scan(int days) {
    int window = Math.min(Math.max(days, 1), 90);
    var since = clock.instant().minusSeconds((long) window * 24 * 3600);
    var out = new ArrayList<AnomalyDto>();
    out.addAll(detectSpikes(since));
    out.addAll(detectReturnAbuse(since));
    out.addAll(detectExpiryRate());
    return out;
  }

  private List<AnomalyDto> detectSpikes(Instant since) {
    var moves = movementRepo.findByTypeAndCreatedAtAfter(MovementType.OUT, since);
    var byProduct = new HashMap<Long, List<StockMovement>>();
    for (var m : moves) {
      byProduct.computeIfAbsent(m.getProduct().getId(), k -> new ArrayList<>()).add(m);
    }
    var out = new ArrayList<AnomalyDto>();
    for (var e : byProduct.entrySet()) {
      var list = e.getValue();
      if (list.size() < 4) {
        continue;
      }
      var latest = list.stream().max(java.util.Comparator.comparing(StockMovement::getId)).orElseThrow();
      var prior = list.stream().filter(m -> !m.getId().equals(latest.getId())).toList();
      if (prior.size() < 3) {
        continue;
      }
      double avg = prior.stream().mapToInt(StockMovement::getQty).average().orElse(0);
      if (avg > 0 && latest.getQty() > 3 * avg) {
        var severity = latest.getQty() > 5 * avg ? "HIGH" : "MEDIUM";
        out.add(new AnomalyDto(AnomalyType.SPIKE, e.getKey(), severity,
            "OUT " + latest.getQty() + " exceeds 3x avg " + avg, clock.instant()));
      }
    }
    return out;
  }

  private List<AnomalyDto> detectReturnAbuse(Instant since) {
    var moves = movementRepo.findByTypeAndCreatedAtAfter(MovementType.IN, since);
    var returns = new HashMap<Long, Integer>();
    var outs = new HashMap<Long, Integer>();
    for (var m : moves) {
      if ("RETURN".equals(m.getRefType())) {
        returns.merge(m.getProduct().getId(), m.getQty(), Integer::sum);
      }
    }
    for (var m : movementRepo.findByTypeAndCreatedAtAfter(MovementType.OUT, since)) {
      outs.merge(m.getProduct().getId(), m.getQty(), Integer::sum);
    }
    var out = new ArrayList<AnomalyDto>();
    for (var e : returns.entrySet()) {
      int ret = e.getValue();
      int o = outs.getOrDefault(e.getKey(), 0);
      if (ret >= 3 && o > 0 && (double) ret / (ret + o) > 0.5) {
        out.add(new AnomalyDto(AnomalyType.HIGH_RETURNS, e.getKey(), "HIGH",
            "returned " + ret + " of " + (ret + o) + " moved", clock.instant()));
      }
    }
    return out;
  }

  private List<AnomalyDto> detectExpiryRate() {
    long expired = reservationRepo.countByStatus(ReservationStatus.EXPIRED);
    long confirmed = reservationRepo.countByStatus(ReservationStatus.CONFIRMED);
    long total = expired + confirmed;
    var out = new ArrayList<AnomalyDto>();
    if (total >= 5 && (double) expired / total > 0.5) {
      out.add(new AnomalyDto(AnomalyType.HIGH_EXPIRY, null, "MEDIUM",
          "expired " + expired + " of " + total + " terminal reservations", clock.instant()));
    }
    return out;
  }
}
```
Required repo additions (both in this task's commit): `StockMovementRepository.findByTypeAndCreatedAtAfter(MovementType, Instant)` (derived query) and `ReservationRepository.countByStatus(ReservationStatus)` (derived). Lazy `m.getProduct()` runs inside the readOnly tx ✓.
Spike trace: 4 OUT rows (2,2,2,20); latest=max id; prior avg=2; 20>6 ✓ 20>10 → HIGH; other products' spikes excluded by productId filter ✓. Quiet: 1 row <4 samples → nothing ✓ (global expiry ignored via productId filter).

- [ ] **Step 3: GREEN**

Run: `./mvnw -q -Dtest=AnomalyIT test` (JDK 21)
Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/sample/inventory/insights/anomaly src/main/java/com/sample/inventory/movement/StockMovementRepository.java src/main/java/com/sample/inventory/order/ReservationRepository.java src/test/java/com/sample/inventory/insights/AnomalyIT.java
git commit -m "feat: add inventory anomaly detection rules"
```

### Task 4: Recommendations

**Files:**
- Create: `insights/recommendation/RecommendationService.java`, `insights/recommendation/RecommendationDto.java`
- Create test: `insights/RecommendationIT.java`

**Interfaces:**
- Consumes: `OrderLineRepository` (+ new co-occurrence query), `ProductRepository`
- Produces: `RecommendationService.forProduct(productId)` top-5 list

- [ ] **Step 1: Write IT first (exact)**

`src/test/java/com/sample/inventory/insights/RecommendationIT.java`:
```java
package com.sample.inventory.insights;

import static org.assertj.core.api.Assertions.assertThat;

import com.sample.inventory.TestcontainersConfiguration;
import com.sample.inventory.insights.recommendation.RecommendationService;
import com.sample.inventory.inventory.Inventory;
import com.sample.inventory.inventory.InventoryRepository;
import com.sample.inventory.movement.StockMovementRepository;
import com.sample.inventory.order.CreateOrderRequest;
import com.sample.inventory.order.CreateOrderRequest.CreateOrderLine;
import com.sample.inventory.order.OrderService;
import com.sample.inventory.order.SalesOrderRepository;
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
class RecommendationIT {

  @Autowired RecommendationService recommendations;
  @Autowired OrderService orders;
  @Autowired SalesOrderRepository orderRepo;
  @Autowired InventoryRepository invRepo;
  @Autowired ProductRepository productRepo;
  @Autowired WarehouseRepository warehouseRepo;
  @Autowired StockMovementRepository movementRepo;

  Product a;
  Product b;
  Product c;

  @BeforeEach
  void seed() {
    movementRepo.deleteAll();
    orderRepo.deleteAll();
    invRepo.deleteAll();
    a = productRepo.save(new Product("A-" + System.nanoTime(), "Alpha"));
    b = productRepo.save(new Product("B-" + System.nanoTime(), "Beta"));
    c = productRepo.save(new Product("C-" + System.nanoTime(), "Gamma"));
    var w = warehouseRepo.save(new Warehouse("W-" + System.nanoTime(), "W", 1));
    invRepo.save(new Inventory(a, w, 100, 0, 1));
    invRepo.save(new Inventory(b, w, 100, 0, 1));
    invRepo.save(new Inventory(c, w, 100, 0, 1));
    // A+B twice, A+C once — all confirmed
    for (int i = 0; i < 2; i++) {
      var o = orders.create(new CreateOrderRequest(List.of(
          new CreateOrderLine(a.getId(), 1), new CreateOrderLine(b.getId(), 1))), null, null);
      orders.confirm(o.id());
    }
    var o3 = orders.create(new CreateOrderRequest(List.of(
        new CreateOrderLine(a.getId(), 1), new CreateOrderLine(c.getId(), 1))), null, null);
    orders.confirm(o3.id());
  }

  @Test
  void recommendsFrequentBasketMate() {
    var recs = recommendations.forProduct(a.getId());
    assertThat(recs).extracting(r -> r.productSku())
        .contains(b.getSku())
        .doesNotContain(c.getSku());
    assertThat(recs.get(0).boughtTogetherCount()).isEqualTo(2);
  }

  @Test
  void unknownProductYieldsEmpty() {
    // product with no orders → empty, not error (differs from forecast: browsing-safe)
    var q = productRepo.save(new Product("Q-" + System.nanoTime(), "Q"));
    assertThat(recommendations.forProduct(q.getId())).isEmpty();
  }
}
```
`recs.get(0)` — is B first? B count 2; other products from SHARED container could also co-occur with A? No — co-occurrence is per OUR orders only (A appears only in our 3 orders). But could ANOTHER product X appear in OUR orders? No. So recs for A = {B:2, C:1}?? C count 1 < MIN_SUPPORT 2 → excluded → recs=[B] only. get(0)=B ✓ deterministic. (MIN_SUPPORT=2 constant.)
Second test: Q has no orders → empty ✓ (and no NotFound — browsing-safe by design).

Run: `./mvnw -q -Dtest=RecommendationIT test`
Expected RED: compilation error.

- [ ] **Step 2: Implement (exact)**

`OrderLineRepository` addition:
```java
  @Query("select l2.product.id, count(l2) from OrderLine l1 join OrderLine l2"
      + " on l2.order = l1.order where l1.product.id = :productId"
      + " and l2.product.id <> :productId and l1.order.status = :status"
      + " and l2.order.status = :status group by l2.product.id"
      + " having count(l2) >= :minSupport order by count(l2) desc")
  List<Object[]> countBasketMates(long productId, OrderStatus status, long minSupport);
```
`count(l2)` returns Long; rows are `Object[]{Long id, Long cnt}`; `l1.order.status` navigation and `JOIN ... ON` are valid Hibernate 7.

`insights/recommendation/RecommendationDto.java`:
```java
package com.sample.inventory.insights.recommendation;

public record RecommendationDto(Long productId, String productSku, long boughtTogetherCount) {}
```

`insights/recommendation/RecommendationService.java`:
```java
package com.sample.inventory.insights.recommendation;

import com.sample.inventory.order.OrderLineRepository;
import com.sample.inventory.order.OrderStatus;
import com.sample.inventory.product.ProductRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecommendationService {

  private static final long MIN_SUPPORT = 2;
  private static final int LIMIT = 5;

  private final OrderLineRepository lineRepo;
  private final ProductRepository productRepo;

  public List<RecommendationDto> forProduct(long productId) {
    var rows = lineRepo.countBasketMates(productId, OrderStatus.CONFIRMED, MIN_SUPPORT);
    var out = new ArrayList<RecommendationDto>();
    for (var row : rows.stream().limit(LIMIT).toList()) {
      Long id = (Long) row[0];
      long cnt = (Long) row[1];
      var sku = productRepo.findById(id).map(p -> p.getSku()).orElse("?");
      out.add(new RecommendationDto(id, sku, cnt));
    }
    return out;
  }
}
```

- [ ] **Step 3: GREEN**

Run: `./mvnw -q -Dtest=RecommendationIT test` (JDK 21)
Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/sample/inventory/insights/recommendation src/main/java/com/sample/inventory/order/OrderLineRepository.java src/test/java/com/sample/inventory/insights/RecommendationIT.java
git commit -m "feat: add bought-together product recommendations"
```

### Task 5: Assistant + controller + verify + README

**Files:**
- Create: `insights/assistant/AssistantProvider.java`, `insights/assistant/RuleBasedAssistantProvider.java`, `insights/assistant/AssistantRequest.java`, `insights/assistant/AssistantResponse.java`, `insights/InsightsController.java`
- Create test: `insights/AssistantTest.java` (Mockito unit — NO Spring context, fast)
- Modify: `README.md` (check AI Features + endpoint rows)

**Interfaces:**
- Consumes: Tasks 1–4 services + `InventoryService`, `OrderService`
- Produces: `POST /insights/assistant`; all 5 endpoints live

- [ ] **Step 1: Write unit test first (exact — Mockito only)**

`src/test/java/com/sample/inventory/insights/AssistantTest.java` (final version below):
Provider deps (final): `ProductService products`, `InventoryService inventories`, `OrderService orders`, `ReplenishmentService replenishment`. Match order: order-status regex → low-stock keywords → stock-sku regex → replenishment keywords → help fallback.
```java
package com.sample.inventory.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.sample.inventory.insights.assistant.RuleBasedAssistantProvider;
import com.sample.inventory.insights.replenishment.ReplenishmentService;
import com.sample.inventory.insights.replenishment.ReplenishmentSuggestion;
import com.sample.inventory.inventory.InventoryResponse;
import com.sample.inventory.inventory.InventoryService;
import com.sample.inventory.order.OrderResponse;
import com.sample.inventory.order.OrderService;
import com.sample.inventory.order.OrderStatus;
import com.sample.inventory.product.ProductResponse;
import com.sample.inventory.product.ProductService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class AssistantTest {

  @Mock ProductService products;
  @Mock InventoryService inventories;
  @Mock OrderService orders;
  @Mock ReplenishmentService replenishment;
  @InjectMocks RuleBasedAssistantProvider assistant;

  @Test
  void answersStockQuery() {
    when(products.search(anyString(), any(Pageable.class))).thenReturn(
        new PageImpl<>(List.of(new ProductResponse(1L, "KB-100", "Keyboard", true))));
    when(inventories.getByProduct(1L)).thenReturn(
        List.of(new InventoryResponse(9L, 1L, "KB-100", 2L, "JKT-1", 5, 0, 2, false)));
    var out = assistant.answer("stok KB-100");
    assertThat(out.intent()).isEqualTo("stock_lookup");
    assertThat(out.answer()).contains("KB-100").contains("5");
  }

  @Test
  void answersLowStock() {
    when(inventories.search(any(), any(), anyBoolean(), any(Pageable.class))).thenReturn(
        new PageImpl<>(List.of(
            new InventoryResponse(9L, 1L, "KB-100", 2L, "JKT-1", 1, 0, 5, true))));
    var out = assistant.answer("low stock apa saja?");
    assertThat(out.intent()).isEqualTo("low_stock");
    assertThat(out.answer()).contains("KB-100");
  }

  @Test
  void answersOrderStatus() {
    when(orders.get(7L)).thenReturn(new OrderResponse(7L, OrderStatus.CONFIRMED, List.of(),
        Instant.parse("2026-09-11T00:00:00Z")));
    var out = assistant.answer("status order 7");
    assertThat(out.intent()).isEqualTo("order_status");
    assertThat(out.answer()).contains("7").contains("CONFIRMED");
  }

  @Test
  void answersReplenishment() {
    when(replenishment.suggestAll()).thenReturn(List.of(
        new ReplenishmentSuggestion(1L, "KB-100", 2, 10.0, 8, "low")));
    var out = assistant.answer("minta restock");
    assertThat(out.intent()).isEqualTo("replenishment");
    assertThat(out.answer()).contains("KB-100").contains("8");
  }

  @Test
  void unknownFallsBackToHelp() {
    var out = assistant.answer("halo apa kabar");
    assertThat(out.intent()).isEqualTo("help");
    assertThat(out.answer()).contains("bisa");
  }
}
```
Mockito strictness: each test stubs only what its path uses (strict-stubs safe); `anyBoolean()` used for the primitive `lowOnly` param; DTO ctor orders verified against Fase 1 definitions.

Run: `./mvnw -q -Dtest=AssistantTest test`
Expected RED: compilation error.

- [ ] **Step 2: Implement (exact)**

`insights/assistant/AssistantProvider.java`:
```java
package com.sample.inventory.insights.assistant;

public interface AssistantProvider {

  AssistantResponse answer(String query);
}
```

`insights/assistant/AssistantRequest.java`:
```java
package com.sample.inventory.insights.assistant;

import jakarta.validation.constraints.NotBlank;

public record AssistantRequest(@NotBlank String query) {}
```

`insights/assistant/AssistantResponse.java`:
```java
package com.sample.inventory.insights.assistant;

public record AssistantResponse(String intent, String answer) {}
```

`insights/assistant/RuleBasedAssistantProvider.java`:
```java
package com.sample.inventory.insights.assistant;

import com.sample.inventory.insights.replenishment.ReplenishmentService;
import com.sample.inventory.inventory.InventoryService;
import com.sample.inventory.order.OrderService;
import com.sample.inventory.product.ProductService;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RuleBasedAssistantProvider implements AssistantProvider {

  private static final Pattern ORDER_ID = Pattern.compile("order\\s+(\\d+)");
  private static final Pattern STOCK_SKU = Pattern.compile("sto[kc]\\s+(\\S+)");

  private final ProductService products;
  private final InventoryService inventories;
  private final OrderService orders;
  private final ReplenishmentService replenishment;

  @Override
  public AssistantResponse answer(String query) {
    String q = query == null ? "" : query.trim().toLowerCase();
    Matcher orderM = ORDER_ID.matcher(q);
    if (orderM.find()) {
      long id = Long.parseLong(orderM.group(1));
      var o = orders.get(id);
      return new AssistantResponse("order_status",
          "Order " + o.id() + " is " + o.status() + " (" + o.lines().size() + " lines)");
    }
    if (q.contains("low stock") || q.contains("stok menipis") || q.contains("stok rendah")) {
      var page = inventories.search(null, null, true, PageRequest.of(0, 5));
      if (page.isEmpty()) {
        return new AssistantResponse("low_stock", "Tidak ada stok menipis. Semua aman.");
      }
      String items = page.getContent().stream()
          .limit(5)
          .map(i -> i.productSku() + "=" + i.available())
          .collect(Collectors.joining(", "));
      return new AssistantResponse("low_stock", "Stok menipis (" + page.getTotalElements() + "): " + items);
    }
    Matcher stockM = STOCK_SKU.matcher(q);
    if (stockM.find()) {
      String sku = stockM.group(1).toUpperCase();
      var matches = products.search(sku, PageRequest.of(0, 5)).getContent();
      if (matches.isEmpty()) {
        return new AssistantResponse("stock_lookup", "Produk " + sku + " tidak ditemukan.");
      }
      var prod = matches.get(0);
      var rows = inventories.getByProduct(prod.id());
      int total = rows.stream().mapToInt(r -> r.available()).sum();
      String detail = rows.stream()
          .map(r -> r.warehouseCode() + "=" + r.available())
          .collect(Collectors.joining(", "));
      return new AssistantResponse("stock_lookup",
          prod.sku() + ": " + total + " available (" + detail + ")");
    }
    if (q.contains("replenish") || q.contains("restock") || q.contains("isi ulang")
        || q.contains("reorder")) {
      var top = replenishment.suggestAll().stream().limit(3).toList();
      if (top.isEmpty()) {
        return new AssistantResponse("replenishment", "Tidak ada yang perlu restock.");
      }
      String items = top.stream()
          .map(s -> s.productSku() + " suggest " + s.suggestedQty()
              + " (avail " + s.available() + ")")
          .collect(Collectors.joining("; "));
      return new AssistantResponse("replenishment", "Restock: " + items);
    }
    return new AssistantResponse("help",
        "Saya bisa: stok <SKU>, low stock, status order <id>, restock. Coba salah satunya.");
  }
}
```
Trace check: "stok KB-100" → stock_lookup (total 5 in answer); "low stock apa saja?" → low_stock (KB-100); "status order 7" → order_status (7, CONFIRMED); "minta restock" → replenishment ("restock" has no `stok\s+` match since "sto" is followed by "c", not space); "halo apa kabar" → help ("bisa"). SKU uppercased before search; `o.lines().size()` valid.

`insights/InsightsController.java`:
```java
package com.sample.inventory.insights;

import com.sample.inventory.common.error.DomainException;
import com.sample.inventory.common.error.ErrorCode;
import com.sample.inventory.common.web.ApiResponse;
import com.sample.inventory.insights.anomaly.AnomalyDto;
import com.sample.inventory.insights.anomaly.AnomalyService;
import com.sample.inventory.insights.assistant.AssistantProvider;
import com.sample.inventory.insights.assistant.AssistantRequest;
import com.sample.inventory.insights.assistant.AssistantResponse;
import com.sample.inventory.insights.forecast.DemandForecastResponse;
import com.sample.inventory.insights.forecast.DemandForecastService;
import com.sample.inventory.insights.recommendation.RecommendationDto;
import com.sample.inventory.insights.recommendation.RecommendationService;
import com.sample.inventory.insights.replenishment.ReplenishmentSuggestion;
import com.sample.inventory.insights.replenishment.ReplenishmentService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/insights")
@RequiredArgsConstructor
public class InsightsController {

  private final DemandForecastService forecast;
  private final ReplenishmentService replenishment;
  private final AnomalyService anomalies;
  private final RecommendationService recommendations;
  private final AssistantProvider assistant;

  @GetMapping("/demand")
  public ResponseEntity<ApiResponse<DemandForecastResponse>> demand(
      @RequestParam long productId,
      @RequestParam(defaultValue = "30") int days) {
    checkDays(days);
    return ResponseEntity.ok(ApiResponse.ok(forecast.forecast(productId, days)));
  }

  @GetMapping("/replenishment")
  public ResponseEntity<ApiResponse<List<ReplenishmentSuggestion>>> replenishment() {
    return ResponseEntity.ok(ApiResponse.ok(replenishment.suggestAll()));
  }

  @GetMapping("/anomalies")
  public ResponseEntity<ApiResponse<List<AnomalyDto>>> anomalies(
      @RequestParam(defaultValue = "30") int days) {
    checkDays(days);
    return ResponseEntity.ok(ApiResponse.ok(anomalies.scan(days)));
  }

  @GetMapping("/recommendations")
  public ResponseEntity<ApiResponse<List<RecommendationDto>>> recommendations(
      @RequestParam long productId) {
    return ResponseEntity.ok(ApiResponse.ok(recommendations.forProduct(productId)));
  }

  @PostMapping("/assistant")
  public ResponseEntity<ApiResponse<AssistantResponse>> assistant(
      @Valid @RequestBody AssistantRequest req) {
    return ResponseEntity.ok(ApiResponse.ok(assistant.answer(req.query())));
  }

  private static void checkDays(int days) {
    if (days < 1 || days > 90) {
      throw new DomainException(ErrorCode.VALIDATION);
    }
  }
}
```

- [ ] **Step 3: GREEN + README + verify**

Run: `./mvnw -q -Dtest=AssistantTest test` (JDK 21)
Expected: `Tests run: 5, Failures: 0, Errors: 0`

README (READ first): check the 5 AI Features boxes + append under `## API`:
```markdown
| GET | /api/v1/insights/demand?productId=&days= | moving-average forecast |
| GET | /api/v1/insights/replenishment | reorder-point suggestions |
| GET | /api/v1/insights/anomalies?days= | rule-based anomalies |
| GET | /api/v1/insights/recommendations?productId= | bought-together top 5 |
| POST | /api/v1/insights/assistant | rule-based inventory Q&A |
```

Run: `./mvnw spotless:apply` then `./mvnw verify`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commits (two, in order)**

```bash
git add src/main/java/com/sample/inventory/insights/assistant src/main/java/com/sample/inventory/insights/InsightsController.java src/test/java/com/sample/inventory/insights/AssistantTest.java README.md
git commit -m "feat: add rule-based inventory assistant and insights api"
```
(Spotless leftovers if any: `git add -A` + `git commit -m "style: apply spotless leftovers from fase 5"` AFTER — inspect first; non-format → NEEDS_CONTEXT.)

### Task 6: Live demo (all five)

**Files:** none (demo only, no commit unless style leftovers — none expected)

- [ ] **Step 1: Boot dev + seed via API**

`$env:SPRING_PROFILES_ACTIVE='dev'; ./mvnw spring-boot:run` (JDK 21). Seed via API (no psql needed!): create product WB-100 + warehouse + PO receive 100 → confirm an order of 60 (creates history) → second product + co-occurrence orders. Record everything. Simpler deterministic script:
1. POST product A-100, B-100; POST warehouse (reuse seed or create W-DMO); PO(A,100)→receive full; PO(B,100)→receive full.
2. Order(A,10 + B,10) ×2 → confirm both; Order(A,5 + C?...) — need C for recommendation exclusion? Keep: 2×(A+B) confirmed.
3. Calls: demand(A,30) → avg 20/30... record values; replenishment → record; anomalies → record (spike? create spike: order B 90? available 80... keep natural: just record output, spike may or may not appear — assert NOTHING in demo, record only); recommendations(A) → B top; assistant: "stok A-100", "low stock", "status order <id>", "restock", "halo".
4. Stop app. All outputs into the report. If any endpoint 500s → BLOCKED with output (do not fix — route back).
