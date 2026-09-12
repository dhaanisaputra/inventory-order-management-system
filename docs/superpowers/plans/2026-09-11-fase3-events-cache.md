# Fase 3 (Events + Cache) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Event-driven + cached: transactional outbox with relay to Kafka, low-stock fire-and-forget publish + alert consumer, Redis read cache for per-product inventory.

**Architecture:** Writers never touch the broker in-tx: order/movement events go to the `outbox` table in the same transaction (V4), a `@Scheduled` relay publishes with `SKIP LOCKED` (multi-instance safe). `stock.low` is fire-and-forget (`try/catch`, never fails the order tx) per spec §4. Reads: `GET /products/{id}/inventory` cached in Redis (TTL 30s); every stock mutation evicts whole `inv` cache (`allEntries`, correct-first at this scale); row locks stay in Postgres — stale cache can never cause oversell.

**Tech Stack:** Java 21, Spring Boot 4.1.1, PostgreSQL 16, Kafka (KRaft, docker), Redis 7, Testcontainers (PG only), JUnit 5 + Mockito + AssertJ. No broker in tests (mocked `KafkaTemplate`; `spring.cache.type=simple` + disabled listener autostart in test resources).

## Global Constraints

- Branch `phase-three` (from `main`); base package `com.sample.inventory`; API prefix `/api/v1`.
- JPA `ddl-auto: validate` — schema only from Flyway (V1–V3 exist; V4 in Task 1).
- Build JDK 21 (`$env:JAVA_HOME='C:\Program Files\Java\jdk-21'`); `mvn clean` on toolchain switch.
- DTOs as records; entity rules; service→DTO, controller→ResponseEntity.
- `@Transactional` services (`readOnly` default); `parallelStream` banned; no H2.
- Boot 4.1.1 test imports; Testcontainers 2.x; page 0-based size 20/100.
- Conventional Commits; `spotless:check` green; do NOT push.
- Topics frozen (spec §13): `inventory.order.created/confirmed/cancelled`, `inventory.stock.low`, `inventory.stock.movement`.

---

## File Structure

```
src/main/resources/db/migration/V4__outbox.sql
src/main/resources/application.yml                    # + kafka serdes, cache, app.outbox (modify)
src/test/resources/application.yml                    # create: kafka listener off, cache simple
src/main/java/com/sample/inventory/
  InventoryOrderManagementSystemApplication.java       # + @EnableCaching (modify, Task 3)
  common/cache/RedisCacheConfig.java                   # create (Task 3)
  common/error/... (untouched)
  events/KafkaTopics.java                              # create (Task 1)
  events/OutboxEvent.java, events/OutboxRepository.java,
    events/OutboxWriter.java, events/OutboxRelayService.java,
    events/OutboxRelayWorker.java                      # create (Task 1)
  events/LowStockEvent.java, events/LowStockNotifier.java  # create (Task 2)
  events/LowStockAlertListener.java                    # create (Task 4)
  movement/MovementWriter.java                         # + outbox hook (modify, Task 2)
  order/OrderService.java                              # + outbox writes + notifier call (modify, Task 2)
  inventory/InventoryRepository.java                   # + findByProductId w/ graph (modify, Task 3)
  inventory/InventoryService.java                      # + getByProduct cached + evict sites (modify, Task 3)
  inventory/InventoryController.java                   # unchanged (endpoint lives on products)
  product/ProductController.java                       # + GET /{id}/inventory (modify, Task 3)
  order/OrderService.java, purchase/PurchaseService.java,
    returns/ReturnService.java, order/ReservationExpiryService.java  # + @CacheEvict (modify, Task 3)
src/test/.../events/OutboxWriterTest.java, OutboxRelayTest.java (Task 1)
src/test/.../events/LowStockNotifierTest.java (Task 2)
src/test/.../inventory/InventoryCacheIT.java (Task 3)
src/test/.../events/LowStockAlertListenerTest.java (Task 4)
```

### Task 1: Outbox infra + test harness

**Files:**
- Create: `V4__outbox.sql`, `events/KafkaTopics.java`, `events/OutboxEvent.java`, `events/OutboxRepository.java`, `events/OutboxWriter.java`, `events/OutboxRelayService.java`, `events/OutboxRelayWorker.java`
- Create: `src/test/resources/application.yml`
- Create test: `events/OutboxWriterTest.java`, `events/OutboxRelayTest.java`

**Interfaces:**
- Consumes: V1–V3 tables; `ObjectMapper` (Boot-provided), `KafkaTemplate` (Boot-provided, lazy)
- Produces: `OutboxWriter.write(topic, key, event)` (MANDATORY); `OutboxRelayService.relayBatch()`; topic constants for Tasks 2–4

- [ ] **Step 1: Write V4 + test yml**

`src/main/resources/db/migration/V4__outbox.sql` (exact, never edit after merge):
```sql
CREATE TABLE outbox (
  id BIGSERIAL PRIMARY KEY,
  topic VARCHAR(128) NOT NULL,
  event_key VARCHAR(128) NOT NULL,
  payload TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_at TIMESTAMPTZ
);
CREATE INDEX ix_outbox_unpublished ON outbox (id) WHERE published_at IS NULL;
```
(Payload is TEXT, not JSONB — deliberate: zero extra dependencies; content is JSON. Deviation from spec §13 recorded here.)

`src/test/resources/application.yml` (exact — keeps every `@SpringBootTest` broker-free):
```yaml
spring:
  kafka:
    listener:
      auto-startup: false
  cache:
    type: simple
```

- [ ] **Step 2: Write failing tests first**

`src/test/java/com/sample/inventory/events/OutboxWriterTest.java` (exact):

```java
package com.sample.inventory.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OutboxWriterTest {

  @Mock OutboxRepository repo;

  @Test
  void writesRowWithTopicKeyAndJsonPayload() {
    var writer = new OutboxWriter(repo, new ObjectMapper());
    writer.write(KafkaTopics.ORDER_CREATED, "42", Map.of("orderId", 42));
    var cap = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(repo).save(cap.capture());
    assertThat(cap.getValue().getTopic()).isEqualTo("inventory.order.created");
    assertThat(cap.getValue().getEventKey()).isEqualTo("42");
    assertThat(cap.getValue().getPayload()).contains("42");
    assertThat(cap.getValue().getPublishedAt()).isNull();
  }
}
```

`src/test/java/com/sample/inventory/events/OutboxRelayTest.java`:
```java
package com.sample.inventory.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

  @Mock OutboxRepository repo;
  @Mock KafkaTemplate<String, String> kafka;

  private final Clock clock = Clock.fixed(Instant.parse("2026-09-11T00:00:00Z"), ZoneOffset.UTC);

  @Test
  @SuppressWarnings("unchecked")
  void publishesUnpublishedAndMarksThem() throws Exception {
    var relay = new OutboxRelayService(repo, kafka, clock);
    var e = new OutboxEvent("inventory.order.created", "7", "{\"orderId\":7}");
    when(repo.lockUnpublished(50)).thenReturn(List.of(e));
    when(kafka.send(eq("inventory.order.created"), eq("7"), anyString()))
        .thenReturn(CompletableFuture.completedFuture(null));
    int n = relay.relayBatch();
    assertThat(n).isEqualTo(1);
    assertThat(e.getPublishedAt()).isEqualTo(clock.instant());
    verify(kafka).send("inventory.order.created", "7", "{\"orderId\":7}");
  }

  @Test
  @SuppressWarnings("unchecked")
  void brokerFailureLeavesRowUnpublished() {
    var relay = new OutboxRelayService(repo, kafka, clock);
    var e = new OutboxEvent("t", "k", "{}");
    when(repo.lockUnpublished(50)).thenReturn(List.of(e));
    when(kafka.send(eq("t"), eq("k"), eq("{}")))
        .thenReturn(CompletableFuture.failedFuture(new RuntimeException("down")));
    int n = relay.relayBatch();
    assertThat(n).isZero();
    assertThat(e.getPublishedAt()).isNull();
  }
}
```
Note: `OutboxEvent` needs a constructor `(topic, key, payload)` + getters + `markPublished(Instant)` (see Step 3). `relayBatch()` takes no args. `@InjectMocks` unused — dropped already above (constructor wiring explicit). `CompletableFuture.failedFuture` needs Java 9+ ✓ (21).

Run: `./mvnw -q -Dtest='OutboxWriterTest,OutboxRelayTest' test`
Expected RED: compilation error (classes do not exist).

- [ ] **Step 3: Implement (exact)**

`events/KafkaTopics.java`:
```java
package com.sample.inventory.events;

public final class KafkaTopics {

  private KafkaTopics() {}

  public static final String ORDER_CREATED = "inventory.order.created";
  public static final String ORDER_CONFIRMED = "inventory.order.confirmed";
  public static final String ORDER_CANCELLED = "inventory.order.cancelled";
  public static final String STOCK_LOW = "inventory.stock.low";
  public static final String STOCK_MOVEMENT = "inventory.stock.movement";
}
```

`events/OutboxEvent.java`:
```java
package com.sample.inventory.events;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 128)
  private String topic;

  @Column(name = "event_key", nullable = false, length = 128)
  private String eventKey;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String payload;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "published_at")
  private Instant publishedAt;

  public OutboxEvent(String topic, String eventKey, String payload) {
    this.topic = topic;
    this.eventKey = eventKey;
    this.payload = payload;
  }

  public void markPublished(Instant at) {
    this.publishedAt = at;
  }
}
```

`events/OutboxRepository.java`:
```java
package com.sample.inventory.events;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

  @Query(value = "SELECT * FROM outbox WHERE published_at IS NULL"
      + " ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
  List<OutboxEvent> lockUnpublished(int limit);
}
```

`events/OutboxWriter.java`:
```java
package com.sample.inventory.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OutboxWriter {

  private final OutboxRepository repo;
  private final ObjectMapper objectMapper;

  @Transactional(propagation = Propagation.MANDATORY)
  public void write(String topic, String key, Object event) {
    try {
      repo.save(new OutboxEvent(topic, key, objectMapper.writeValueAsString(event)));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("outbox serialize failed", e);
    }
  }
}
```

`events/OutboxRelayService.java`:
```java
package com.sample.inventory.events;

import java.time.Clock;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OutboxRelayService {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelayService.class);
  private static final int BATCH_SIZE = 50;

  private final OutboxRepository repo;
  private final KafkaTemplate<String, String> kafka;
  private final Clock clock;

  @Transactional
  public int relayBatch() {
    var batch = repo.lockUnpublished(BATCH_SIZE);
    int sent = 0;
    for (var e : batch) {
      try {
        kafka.send(e.getTopic(), e.getEventKey(), e.getPayload()).get(5, TimeUnit.SECONDS);
      } catch (Exception ex) {
        if (ex instanceof InterruptedException ie) {
          Thread.currentThread().interrupt();
        }
        log.warn("Outbox publish failed, will retry later", ex);
        break;
      }
      e.markPublished(clock.instant());
      sent++;
    }
    return sent;
  }
}
```

`events/OutboxRelayWorker.java`:
```java
package com.sample.inventory.events;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutboxRelayWorker {

  private final OutboxRelayService relay;

  @Scheduled(fixedDelayString = "${app.outbox.relay-interval:PT10S}")
  public void run() {
    relay.relayBatch();
  }
}
```

- [ ] **Step 4: GREEN**

Run: `./mvnw -q -Dtest='OutboxWriterTest,OutboxRelayTest' test` (JDK 21)
Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V4__outbox.sql src/test/resources/application.yml src/main/java/com/sample/inventory/events src/test/java/com/sample/inventory/events
git commit -m "feat: add transactional outbox with kafka relay"
```

### Task 2: Order/movement events + low-stock notifier

**Files:**
- Create: `events/LowStockEvent.java`, `events/LowStockNotifier.java`
- Modify: `movement/MovementWriter.java` (+ outbox hook), `order/OrderService.java` (+ 3 outbox writes + notifier call)
- Create test: `events/LowStockNotifierTest.java`

**Interfaces:**
- Consumes: Task 1 (`OutboxWriter`, `KafkaTopics`)
- Produces: events on every state change; `LowStockNotifier.notifyIfLow` for Task 4's topic

- [ ] **Step 1: Write notifier test first (exact)**

`src/test/java/com/sample/inventory/events/LowStockNotifierTest.java`:
```java
package com.sample.inventory.events;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sample.inventory.inventory.Inventory;
import com.sample.inventory.product.Product;
import com.sample.inventory.warehouse.Warehouse;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class LowStockNotifierTest {

  @Mock KafkaTemplate<String, String> kafka;

  private LowStockNotifier notifier() {
    return new LowStockNotifier(kafka, new ObjectMapper());
  }

  private Inventory inv(int available, int threshold) {
    var p = new Product("SKU-1", "Keyboard");
    ReflectionTestUtils.setField(p, "id", 11L);
    var w = new Warehouse("JKT-1", "Jakarta", 10);
    ReflectionTestUtils.setField(w, "id", 22L);
    return new Inventory(p, w, available, 0, threshold);
  }

  @Test
  @SuppressWarnings("unchecked")
  void publishesWhenLow() {
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(null));
    notifier().notifyIfLow(inv(2, 5));
    verify(kafka).send(eq("inventory.stock.low"), eq("11"), anyString());
  }

  @Test
  void silentWhenHealthy() {
    notifier().notifyIfLow(inv(10, 5));
    verify(kafka, never()).send(anyString(), anyString(), anyString());
  }

  @Test
  void brokerFailureIsSwallowed() {
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.failedFuture(new RuntimeException("down")));
    assertThatNoException()
        .isThrownBy(() -> notifier().notifyIfLow(inv(1, 5)));
  }
}
```

Run: `./mvnw -q -Dtest=LowStockNotifierTest test`
Expected RED: compilation error.

- [ ] **Step 2: Implement (exact)**

`events/LowStockEvent.java`:
```java
package com.sample.inventory.events;

public record LowStockEvent(long productId, long warehouseId, int available, int threshold) {}
```

`events/LowStockNotifier.java` (fire-and-forget by design: try/catch around send, no `.get()`):
```java
package com.sample.inventory.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sample.inventory.inventory.Inventory;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LowStockNotifier {

  private static final Logger log = LoggerFactory.getLogger(LowStockNotifier.class);

  private final KafkaTemplate<String, String> kafka;
  private final ObjectMapper objectMapper;

  public void notifyIfLow(Inventory inv) {
    if (!inv.isLowStock()) {
      return;
    }
    try {
      var event = new LowStockEvent(inv.getProduct().getId(), inv.getWarehouse().getId(),
          inv.getAvailable(), inv.getLowStockThreshold());
      kafka.send(KafkaTopics.STOCK_LOW, String.valueOf(inv.getProduct().getId()),
          objectMapper.writeValueAsString(event));
    } catch (Exception e) {
      log.warn("Failed to publish low-stock event", e);
    }
  }
}
```

`MovementWriter` — add `OutboxWriter` dep + hook (read file first; append write AFTER `repo.save`, same MANDATORY tx):
```java
  public void write(Product product, Warehouse warehouse, MovementType type,
      int qty, String refType, long refId) {
    repo.save(StockMovement.of(product, warehouse, type, qty, refType, refId));
    outbox.write(KafkaTopics.STOCK_MOVEMENT, String.valueOf(product.getId()),
        java.util.Map.of("productId", product.getId(), "warehouseId", warehouse.getId(),
            "type", type.name(), "qty", qty, "refType", refType, "refId", refId));
  }
```
Use proper `import java.util.Map;` (not fully-qualified). Field `private final OutboxWriter outbox;` + import `com.sample.inventory.events.KafkaTopics`, `com.sample.inventory.events.OutboxWriter`.

`OrderService` — 4 edits (read file first):
1. Add fields `private final OutboxWriter outbox;` + `private final LowStockNotifier notifier;` (+2 imports).
2. In `create()`, after the idempotency block, before `return get(order.getId());`:
```java
    outbox.write(KafkaTopics.ORDER_CREATED, String.valueOf(order.getId()),
        Map.of("orderId", order.getId(), "status", order.getStatus().name()));
```
Needs `import java.util.Map;` — check existing imports first (OrderService may already import Map/HashMap from hardening task; add only if missing).
3. In `confirm()`, after `order.confirm();`:
```java
    outbox.write(KafkaTopics.ORDER_CONFIRMED, String.valueOf(order.getId()),
        Map.of("orderId", order.getId(), "status", order.getStatus().name()));
```
(NOT on the idempotent early-return path — replays publish nothing.)
4. In `cancel()`, after `order.cancel();`: same with `ORDER_CANCELLED`.
5. In `confirm()` loop, after `inv.confirm(alloc.getQty());`: add `notifier.notifyIfLow(inv);`
   (Only confirm reduces `available` among Fase 1–2 flows that exist so far — receive/returns only add. Transfer comes Fase 4.)

- [ ] **Step 3: GREEN**

Run: `./mvnw -q -Dtest='LowStockNotifierTest,OutboxWriterTest,OutboxRelayTest' test` (JDK 21)
Expected: all green (3+1+2 = 6).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/sample/inventory/events/LowStockEvent.java src/main/java/com/sample/inventory/events/LowStockNotifier.java src/main/java/com/sample/inventory/movement/MovementWriter.java src/main/java/com/sample/inventory/order/OrderService.java src/test/java/com/sample/inventory/events/LowStockNotifierTest.java
git commit -m "feat: publish order and movement events plus low-stock notify"
```

### Task 3: Redis cache + product inventory endpoint

**Files:**
- Create: `common/cache/RedisCacheConfig.java`
- Modify: `InventoryOrderManagementSystemApplication.java` (+ `@EnableCaching`), `src/main/resources/application.yml` (+ cache/kafka/props), `inventory/InventoryRepository.java` (+ `findByProductId`), `inventory/InventoryService.java` (+ cached `getByProduct`), `product/ProductController.java` (+ endpoint), `order/OrderService.java` + `purchase/PurchaseService.java` + `returns/ReturnService.java` + `order/ReservationExpiryService.java` (+ `@CacheEvict`)
- Create test: `inventory/InventoryCacheIT.java`

**Interfaces:**
- Consumes: Tasks 1–2 (nothing breaking); test yml (`cache: simple`) from Task 1
- Produces: `GET /api/v1/products/{id}/inventory` (cached); mutations evict

- [ ] **Step 1: Write cache IT first (exact)**

`src/test/java/com/sample/inventory/inventory/InventoryCacheIT.java`:
```java
package com.sample.inventory.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.sample.inventory.TestcontainersConfiguration;
import com.sample.inventory.movement.StockMovementRepository;
import com.sample.inventory.product.Product;
import com.sample.inventory.product.ProductRepository;
import com.sample.inventory.purchase.CreatePurchaseRequest;
import com.sample.inventory.purchase.CreatePurchaseRequest.CreatePurchaseLine;
import com.sample.inventory.purchase.PurchaseOrderRepository;
import com.sample.inventory.purchase.PurchaseService;
import com.sample.inventory.purchase.ReceiveRequest;
import com.sample.inventory.purchase.ReceiveRequest.ReceiveItem;
import com.sample.inventory.warehouse.Warehouse;
import com.sample.inventory.warehouse.WarehouseRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class InventoryCacheIT {

  @Autowired InventoryService inventories;
  @Autowired PurchaseService purchases;
  @Autowired CacheManager cacheManager;
  @Autowired InventoryRepository invRepo;
  @Autowired PurchaseOrderRepository poRepo;
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
  void mutationEvictsCachedProductStock() {
    var before = inventories.getByProduct(p.getId());
    assertThat(before.get(0).available()).isZero();
    assertThat(cacheManager.getCache("inv").get(p.getId())).isNotNull();
    var po = purchases.create(new CreatePurchaseRequest(
        List.of(new CreatePurchaseLine(p.getId(), 7))));
    purchases.receive(po.id(), new ReceiveRequest(
        List.of(new ReceiveItem(p.getId(), w.getId(), 7))));
    var after = inventories.getByProduct(p.getId());
    assertThat(after.get(0).available()).isEqualTo(7);
  }
}
```
Note: `before.get(0)` — single warehouse seeded. `cacheManager.getCache("inv").get(p.getId())` proves population under the `simple` test provider; the post-mutation read proves eviction (stale would still be 0).

Run: `./mvnw -q -Dtest=InventoryCacheIT test`
Expected RED: compilation error (`getByProduct` missing; `purchase` classes — wait, purchase EXISTS (Fase 2 merged). Only `getByProduct` + cache config missing → compilation error on service method. Good.)

- [ ] **Step 2: Implement (exact)**

`common/cache/RedisCacheConfig.java`:
```java
package com.sample.inventory.common.cache;

import java.time.Duration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
@EnableCaching
public class RedisCacheConfig {

  @Bean
  public RedisCacheConfiguration cacheConfiguration() {
    return RedisCacheConfiguration.defaultCacheConfig()
        .entryTtl(Duration.ofSeconds(30))
        .disableCachingNullValues()
        .serializeKeysWith(SerializationPair.fromSerializer(new StringRedisSerializer()))
        .serializeValuesWith(
            SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer()));
  }
}
```
(Redis key for product 7 becomes `inv::7` — Spring's `name::key` namespacing; documented as the `inv:{productId}` intent from spec §8.)

`InventoryRepository` addition:
```java
  @EntityGraph(attributePaths = {"product", "warehouse"})
  List<Inventory> findByProductId(long productId);
```
(`java.util.List` already imported.)

`InventoryService` addition (imports `org.springframework.cache.annotation.Cacheable`, `CacheEvict` NOT needed here):
```java
  @Cacheable(value = "inv", key = "#productId")
  public List<InventoryResponse> getByProduct(long productId) {
    return repo.findByProductId(productId).stream()
        .map(InventoryMapper::toResponse)
        .toList();
  }
```
Needs `java.util.List` import — check file first.

Evictions — add `@CacheEvict(value = "inv", allEntries = true)` (+ import `org.springframework.cache.annotation.CacheEvict`) on:
- `OrderService.create`, `OrderService.confirm`, `OrderService.cancel`
- `PurchaseService.receive`
- `ReturnService.create`
- `ReservationExpiryService.expireBatch`
(allEntries: correct-first at this catalog scale; keyed eviction when it grows — ponytail: no comment needed, it reads as intent.)

`ProductController` addition (read file first; inject `InventoryService` + import `com.sample.inventory.inventory.InventoryResponse`, `InventoryService`, `java.util.List`):
```java
  @GetMapping("/{id}/inventory")
  public ResponseEntity<ApiResponse<List<InventoryResponse>>> inventory(@PathVariable long id) {
    return ResponseEntity.ok(ApiResponse.ok(inventories.getByProduct(id)));
  }
```
Field: `private final InventoryService inventories;` (RequiredArgsConstructor already present — verify).

`application.yml` additions — READ file first, merge (exact blocks):
```yaml
spring:
  cache:
    type: redis
    redis:
      time-to-live: 30s
      key-prefix: ""
  kafka:
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
    consumer:
      group-id: inventory-alerts
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
app:
  outbox:
    relay-interval: PT10S
```
(`spring.kafka.bootstrap-servers` already exists — keep. `app.reservation.ttl` exists — keep. `key-prefix: ""` keeps keys short.)

- [ ] **Step 3: GREEN**

Run: `./mvnw -q -Dtest=InventoryCacheIT test` (JDK 21)
Expected: `Tests run: 1, Failures: 0, Errors: 0`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/sample/inventory/common/cache src/main/java/com/sample/inventory/InventoryOrderManagementSystemApplication.java src/main/resources/application.yml src/main/java/com/sample/inventory/inventory src/main/java/com/sample/inventory/product/ProductController.java src/main/java/com/sample/inventory/order/OrderService.java src/main/java/com/sample/inventory/purchase/PurchaseService.java src/main/java/com/sample/inventory/returns/ReturnService.java src/main/java/com/sample/inventory/order/ReservationExpiryService.java src/test/java/com/sample/inventory/inventory/InventoryCacheIT.java
git commit -m "feat: cache product inventory in redis with eviction"
```

### Task 4: Low-stock alert consumer

**Files:**
- Create: `events/LowStockAlertListener.java`
- Create test: `events/LowStockAlertListenerTest.java`

**Interfaces:**
- Consumes: Task 2 (`KafkaTopics.STOCK_LOW`)
- Produces: alert side-effect (log stub; mail/webhook later)

- [ ] **Step 1: Write test first (exact)**

`src/test/java/com/sample/inventory/events/LowStockAlertListenerTest.java`:
```java
package com.sample.inventory.events;

import static org.assertj.core.api.Assertions.assertThatNoException;

import org.junit.jupiter.api.Test;

class LowStockAlertListenerTest {

  @Test
  void listenerAcceptsPayloadWithoutThrowing() {
    assertThatNoException().isThrownBy(
        () -> new LowStockAlertListener().onMessage("{\"productId\":11}"));
  }
}
```

Run: `./mvnw -q -Dtest=LowStockAlertListenerTest test`
Expected RED: compilation error.

- [ ] **Step 2: Implement (exact)**

`events/LowStockAlertListener.java`:
```java
package com.sample.inventory.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class LowStockAlertListener {

  private static final Logger log = LoggerFactory.getLogger(LowStockAlertListener.class);

  @KafkaListener(topics = KafkaTopics.STOCK_LOW, groupId = "inventory-alerts")
  public void onMessage(String payload) {
    log.warn("LOW STOCK alert: {}", payload);
  }
}
```
(Listener autostart is disabled in tests via `src/test/resources/application.yml` from Task 1 — no broker needed. Stub: mail/webhook integration later.)

- [ ] **Step 3: GREEN**

Run: `./mvnw -q -Dtest=LowStockAlertListenerTest test` (JDK 21)
Expected: `Tests run: 1, Failures: 0, Errors: 0`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/sample/inventory/events/LowStockAlertListener.java src/test/java/com/sample/inventory/events/LowStockAlertListenerTest.java
git commit -m "feat: consume low-stock alerts with logging stub"
```

### Task 5: Verify + demo + README

**Files:**
- Modify: `README.md` (events + cache rows)

**Interfaces:**
- Consumes: everything (final gate Fase 3)
- Produces: green `verify`, live Kafka+Redis demo proof, updated README

- [ ] **Step 1: Full verify + format**

Run: `./mvnw spotless:apply` then `./mvnw verify` (JDK 21)
Expected: BUILD SUCCESS — surefire + failsafe green, `spotless:check` clean. Non-format changes from apply → NEEDS_CONTEXT.

- [ ] **Step 2: Live demo (docker kafka + redis, dev profile, dev DB only)**

1. Read `application.yml`; ensure configured PG reachable (do NOT wipe/reconfigure).
2. Run: `docker compose up -d kafka redis` (postgres service: reuse running native/dev DB as-is; if the compose `postgres` is stopped AND native PG owns 5432, leave compose-postgres stopped — app uses the configured DB either way).
3. Start: `$env:SPRING_PROFILES_ACTIVE='dev'; ./mvnw spring-boot:run` (JDK 21), wait Started. Confirm in log: relay worker running (no errors), no Kafka connection failures (broker is up).
4. Resolve ids: `GET /api/v1/products?q=KB-100`, `GET /api/v1/warehouses`.
5. Make stock low: via psql on the dev DB (credentials from yml):
   ```sql
   INSERT INTO inventory (product_id, warehouse_id, available, reserved, low_stock_threshold)
   VALUES (<pid>, <wid>, 10, 0, 8)
   ON CONFLICT (product_id, warehouse_id)
   DO UPDATE SET available = 10, low_stock_threshold = 8;
   ```
6. `POST /api/v1/orders` `{"lines":[{"productId":<pid>,"qty":5}]}` → 201 (available 10→5).
7. `POST /api/v1/orders/<id>/confirm` → 200 (available 5→0... wait 5-5=0 <8 → low fires. Hmm available after reserve=5, after confirm available stays 5? Confirm only decrements RESERVED (available was decremented at reserve). 10-5=5 available, threshold 8 → 5 < 8 → LOW at confirm time ✓).
8. Evidence to capture:
   - App log: outbox relay published `inventory.order.created/confirmed` + `inventory.stock.movement`(s).
   - App log: `LOW STOCK alert: {...}` from the consumer (stock.low fire-and-forget → relay? NO — stock.low is direct publish, consumed live).
   - `GET /api/v1/products/<pid>/inventory` twice → same data (cached); `docker exec inventory-redis redis-cli keys '*'` → `inv::<pid>` present. (If compose redis has a different container name, use the actual one from `docker ps`.)
   - `GET /api/v1/stock-movements` → RESERVE + OUT rows.
9. Stop app. Record ALL outputs. If Kafka/Redis containers fail to start, report NEEDS_CONTEXT with `docker` output — do not reconfigure the app.

- [ ] **Step 3: README + commit**

Append under `## API` (read README first, no duplication):

```markdown
| GET | /api/v1/products/{id}/inventory | per-warehouse stock, Redis cached (30s) |
| GET | /api/v1/stock-movements | (existing — now also emits Kafka events) |

Events (Kafka): `inventory.order.created|confirmed|cancelled`, `inventory.stock.movement` via transactional outbox + relay (10s); `inventory.stock.low` fire-and-forget on confirm, logged by alert consumer.
```

```bash
git add README.md
git commit -m "docs: document fase 3 events and cache"
```

## Plan Complete — Fase 4 Preview

Next plan (separate file): warehouse transfers (double-entry movement, global lock order), actuator hardening, docker demo polish. Plus if still open: keyed cache eviction, mail/webhook alert sink.
