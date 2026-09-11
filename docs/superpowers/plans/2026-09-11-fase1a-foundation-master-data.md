# Fase 1a (Foundation + Master Data) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Foundation (Flyway, Testcontainers, springdoc, Spotless) plus master-data modules product & warehouse yang live dengan dokumentasi Swagger.

**Architecture:** Package-by-feature (`product/`, `warehouse/`, `common/`). Service return DTO record, controller return `ResponseEntity<ApiResponse<…>>`, error via `ErrorCode` enum + satu `@RestControllerAdvice`. Schema via Flyway V1 (full core tables, dipakai Fase 1b tanpa migrasi baru).

**Tech Stack:** Java 21, Spring Boot 4.1.1, PostgreSQL 16, Flyway, springdoc-openapi 3.1.0, Testcontainers (postgres:16-alpine), Spotless 3.10.2 + google-java-format, JUnit 5 + Mockito + AssertJ.

## Global Constraints

- Base package `com.sample.inventory`; API prefix `/api/v1`.
- JPA `ddl-auto: validate` — tabel hanya dari Flyway, tidak dari Hibernate.
- `page` 0-based; `size` default 20 max 100 via `spring.data.web.pageable`.
- Tanpa `@Data`/`@EqualsAndHashCode` di entity; `equals/hashCode` pakai business key.
- Tanpa H2; test DB hanya Testcontainers Postgres. `parallelStream` dilarang.
- Conventional Commits; `spotless:check` harus hijau tiap task.
- Build JDK: 21 (`$env:JAVA_HOME='C:\Program Files\Java\jdk-21'`); JDK 26 default merusak Lombok. `mvn clean` setiap ganti toolchain.

---

## File Structure

```
src/main/resources/db/migration/V1__core_schema.sql   # semua tabel Fase 1 (product s/d stock_movement)
src/main/resources/application.yml                    # + flyway, pageable defaults
src/main/java/com/sample/inventory/
  common/web/ApiResponse.java                         # record(data, error) + ok()/fail()
  common/web/PagedResult.java                         # record + from(Page)
  common/web/SortValidator.java                       # whitelist sort per endpoint
  common/error/ErrorCode.java                         # enum code + http + message template
  common/error/DomainException.java                   # base: code + args
  common/error/NotFoundException.java
  common/error/DuplicateException.java
  common/error/GlobalExceptionHandler.java            # @RestControllerAdvice
  common/config/ClockConfig.java                      # Clock.systemUTC bean
  common/dev/DevSeeder.java                           # @Profile("dev") sample data
  product/Product.java, ProductRepository.java,
    ProductRequest.java, UpdateProductRequest.java, ProductResponse.java,
    ProductMapper.java, ProductService.java, ProductController.java
  warehouse/ (pola sama: Warehouse*.java)
src/test/java/com/sample/inventory/
  TestcontainersConfiguration.java                    # @TestConfiguration + @ServiceConnection PG
  product/ProductRepositoryIT.java, ProductServiceTest.java, ProductControllerTest.java
  warehouse/WarehouseRepositoryIT.java, WarehouseServiceTest.java, WarehouseControllerTest.java
```

### Task 1: Dependencies (pom)

**Files:**
- Modify: `pom.xml`

**Interfaces:**
- Consumes: nothing
- Produces: Flyway/springdoc/Testcontainers/Spotless on classpath for all later tasks

- [ ] **Step 1: Add dependencies + plugins to `pom.xml`**

```xml
<properties>
  <java.version>21</java.version>
  <spotless.version>3.10.2</spotless.version>
</properties>
```

dependencies (inside `<dependencies>`):

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-flyway</artifactId>
</dependency>
<dependency>
  <groupId>org.flywaydb</groupId>
  <artifactId>flyway-database-postgresql</artifactId>
  <scope>runtime</scope>
</dependency>
<dependency>
  <groupId>org.springdoc</groupId>
  <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
  <version>3.1.0</version>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-testcontainers</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-data-jpa-test</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-webmvc-test</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>testcontainers-junit-jupiter</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>testcontainers-postgresql</artifactId>
  <scope>test</scope>
</dependency>
```

build plugins (inside `<build><plugins>`, next to boot plugin):

```xml
<plugin>
  <groupId>com.diffplug.spotless</groupId>
  <artifactId>spotless-maven-plugin</artifactId>
  <version>${spotless.version}</version>
  <configuration>
    <java>
      <googleJavaFormat />
    </java>
  </configuration>
  <executions>
    <execution>
      <goals>
        <goal>check</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

- [ ] **Step 2: Verify build resolves**

Run: `./mvnw -q -DskipTests compile`
Expected: exit 0, no output

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "build: add flyway, springdoc, testcontainers, spotless"
```

### Task 2: V1 schema + yml config

**Files:**
- Create: `src/main/resources/db/migration/V1__core_schema.sql`
- Modify: `src/main/resources/application.yml`

**Interfaces:**
- Consumes: nothing
- Produces: tables `product, warehouse, inventory, sales_order, order_line, allocation, reservation, stock_movement` for Tasks 4–6 and Fase 1b

- [ ] **Step 1: Write `V1__core_schema.sql`** (never edit after merge)

```sql
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
```

- [ ] **Step 2: Append to `application.yml`**

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
  data:
    web:
      pageable:
        default-page-size: 20
        max-page-size: 100
```

- [ ] **Step 3: Verify migration against local Postgres**

Run: `docker compose up -d postgres` then `./mvnw -q -DskipTests spring-boot:run` (stop with Ctrl+C after `Started ...Application`), then:

Run: `docker exec inventory-postgres psql -U inventory -d inventory_management -c "\dt"`
Expected: 8 tables listed (`allocation, inventory, order_line, product, reservation, sales_order, stock_movement, warehouse`)

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/db/migration/V1__core_schema.sql src/main/resources/application.yml
git commit -m "feat: add V1 core schema migration and pageable defaults"
```

### Task 3: Testcontainers support

**Files:**
- Create: `src/test/java/com/sample/inventory/TestcontainersConfiguration.java`

**Interfaces:**
- Consumes: Task 1 (testcontainers deps)
- Produces: `postgres:16-alpine` `@ServiceConnection` bean imported by every `*IT` test

- [ ] **Step 1: Write the configuration + a smoke test**

```java
package com.sample.inventory;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  @Bean
  @ServiceConnection
  PostgreSQLContainer<?> postgresContainer() {
    return new PostgreSQLContainer<>("postgres:16-alpine");
  }
}
```

Append to existing `InventoryOrderManagementSystemApplicationTests.java` — no; create
`src/test/java/com/sample/inventory/PostgresSmokeIT.java`:

```java
package com.sample.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PostgresSmokeIT {

  @Autowired PostgreSQLContainer<?> postgres;

  @Test
  void containerIsRunning() {
    assertThat(postgres.isRunning()).isTrue();
  }
}
```

- [ ] **Step 2: Run to verify it passes**

Run: `./mvnw -q -Dtest=PostgresSmokeIT test`
Expected: `Tests run: 1, Failures: 0, Errors: 0`

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/sample/inventory/TestcontainersConfiguration.java src/test/java/com/sample/inventory/PostgresSmokeIT.java
git commit -m "test: add testcontainers postgres support with smoke test"
```

### Task 4: Common web + error + config

**Files:**
- Create: `common/web/ApiResponse.java`, `common/web/PagedResult.java`, `common/web/SortValidator.java`
- Create: `common/error/ErrorCode.java`, `common/error/DomainException.java`, `common/error/NotFoundException.java`, `common/error/DuplicateException.java`, `common/error/GlobalExceptionHandler.java`
- Create: `common/config/ClockConfig.java`
- Create: `common/web/PagedResultTest.java`, `common/web/SortValidatorTest.java` (in `src/test/.../common/web/`)

**Interfaces:**
- Consumes: nothing
- Produces: `ApiResponse.ok/fail`, `PagedResult.from(Page)`, `SortValidator.validated(Pageable, Set, Sort)`, `ErrorCode` (with `httpStatus()` + `format(args)`), `DomainException.getCode/getArgs`, `Clock` bean — used by Tasks 5–6

- [ ] **Step 1: Write failing tests first**

`src/test/java/com/sample/inventory/common/web/InventoryCommonTest.java` (single class to keep it tight):

```java
package com.sample.inventory.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class InventoryCommonTest {

  @Test
  void pagedResultMapsFromSpringPage() {
    var page = new PageImpl<>(List.of("a", "b"), PageRequest.of(1, 20), 42);
    var result = PagedResult.from(page);
    assertThat(result.content()).containsExactly("a", "b");
    assertThat(result.page()).isEqualTo(1);
    assertThat(result.size()).isEqualTo(20);
    assertThat(result.totalElements()).isEqualTo(42);
    assertThat(result.totalPages()).isEqualTo(3);
  }

  @Test
  void sortValidatorFallsBackOnUnknownProperty() {
    Pageable in = PageRequest.of(0, 20, Sort.by("hacker"));
    Pageable out = SortValidator.validated(in, Set.of("name"), Sort.by("name").ascending());
    assertThat(out.getSort().getOrderFor("name")).isNotNull();
    assertThat(out.getSort().getOrderFor("hacker")).isNull();
  }

  @Test
  void sortValidatorKeepsAllowedSort() {
    Pageable in = PageRequest.of(0, 20, Sort.by("name").descending());
    Pageable out = SortValidator.validated(in, Set.of("name"), Sort.by("name").ascending());
    assertThat(out.getSort().getOrderFor("name").getDirection()).isEqualTo(Sort.Direction.DESC);
  }
}
```

Run: `./mvnw -q -Dtest=InventoryCommonTest test`
Expected: FAIL — compilation error (`PagedResult`/`SortValidator` do not exist)

- [ ] **Step 2: Write minimal implementation**

`common/web/ApiResponse.java`:

```java
package com.sample.inventory.common.web;

public record ApiResponse<T>(T data, ApiError error) {

  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(data, null);
  }

  public static <T> ApiResponse<T> fail(String code, String message) {
    return new ApiResponse<>(null, new ApiError(code, message));
  }

  public record ApiError(String code, String message) {}
}
```

`common/web/PagedResult.java`:

```java
package com.sample.inventory.common.web;

import java.util.List;
import org.springframework.data.domain.Page;

public record PagedResult<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

  public static <T> PagedResult<T> from(Page<T> page) {
    return new PagedResult<>(
        page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
  }
}
```

`common/web/SortValidator.java`:

```java
package com.sample.inventory.common.web;

import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public final class SortValidator {

  private SortValidator() {}

  public static Pageable validated(Pageable pageable, Set<String> allowed, Sort defaultSort) {
    boolean ok = pageable.getSort().isSorted()
        && pageable.getSort().stream().map(Sort.Order::getProperty).allMatch(allowed::contains);
    if (ok) {
      return pageable;
    }
    return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), defaultSort);
  }
}
```

`common/error/ErrorCode.java`:

```java
package com.sample.inventory.common.error;

public enum ErrorCode {
  VALIDATION(400, "validation failed"),
  NOT_FOUND(404, "resource not found"),
  DUPLICATE(409, "{0} already exists"),
  INTERNAL(500, "internal error");

  private final int httpStatus;
  private final String template;

  ErrorCode(int httpStatus, String template) {
    this.httpStatus = httpStatus;
    this.template = template;
  }

  public int httpStatus() {
    return httpStatus;
  }

  public String format(Object... args) {
    String out = template;
    for (int i = 0; i < args.length; i++) {
      out = out.replace("{" + i + "}", String.valueOf(args[i]));
    }
    return out;
  }
}
```

> Spec amendment (write back to spec §7 on implementation): codes `DUPLICATE (409)` and
> `INTERNAL (500)` added — needed by master-data uniqueness and the fallback handler.

`common/error/DomainException.java`:

```java
package com.sample.inventory.common.error;

public class DomainException extends RuntimeException {

  private final ErrorCode code;
  private final Object[] args;

  public DomainException(ErrorCode code, Object... args) {
    super(code.format(args));
    this.code = code;
    this.args = args;
  }

  public ErrorCode getCode() {
    return code;
  }
}
```

`common/error/NotFoundException.java`:

```java
package com.sample.inventory.common.error;

public class NotFoundException extends DomainException {

  public NotFoundException(String resource, Object id) {
    super(ErrorCode.NOT_FOUND, resource + " " + id);
  }
}
```

`common/error/DuplicateException.java`:

```java
package com.sample.inventory.common.error;

public class DuplicateException extends DomainException {

  public DuplicateException(String resource, Object key) {
    super(ErrorCode.DUPLICATE, resource + " " + key);
  }
}
```

`common/error/GlobalExceptionHandler.java`:

```java
package com.sample.inventory.common.error;

import com.sample.inventory.common.web.ApiResponse;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(DomainException.class)
  public ResponseEntity<ApiResponse<Void>> handleDomain(DomainException ex) {
    ErrorCode code = ex.getCode();
    return ResponseEntity.status(code.httpStatus()).body(ApiResponse.fail(code.name(), ex.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
    String message = ex.getBindingResult().getFieldErrors().stream()
        .map(e -> e.getField() + " " + e.getDefaultMessage())
        .collect(Collectors.joining("; "));
    return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.VALIDATION.name(), message));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> handleFallback(Exception ex) {
    return ResponseEntity.internalServerError()
        .body(ApiResponse.fail(ErrorCode.INTERNAL.name(), ErrorCode.INTERNAL.format()));
  }
}
```

`common/config/ClockConfig.java`:

```java
package com.sample.inventory.common.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
```

- [ ] **Step 3: Run tests to verify they pass**

Run: `./mvnw -q -Dtest=InventoryCommonTest test`
Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/sample/inventory/common src/test/java/com/sample/inventory/common
git commit -m "feat: add common api response, errors, sort validator, clock"
```

### Task 5: Product module

**Files:**
- Create: `product/Product.java`, `product/ProductRepository.java`, `product/ProductRequest.java`, `product/UpdateProductRequest.java`, `product/ProductResponse.java`, `product/ProductMapper.java`, `product/ProductService.java`, `product/ProductController.java`
- Create: `product/ProductRepositoryIT.java`, `product/ProductServiceTest.java`, `product/ProductControllerTest.java`

**Interfaces:**
- Consumes: Task 2 (table `product`), Task 4 (`ApiResponse`, `PagedResult`, `SortValidator`, `NotFoundException`, `DuplicateException`)
- Produces: `ProductService.create/get/search/update` (DTO in/out), `POST/GET/PATCH /api/v1/products`

- [ ] **Step 1: Write the failing repository IT first**

`src/test/java/com/sample/inventory/product/ProductRepositoryIT.java`:

```java
package com.sample.inventory.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.sample.inventory.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
class ProductRepositoryIT {

  @Autowired ProductRepository repo;

  @Test
  void persistsAndFindsBySku() {
    repo.save(new Product("SKU-1", "Keyboard"));
    assertThat(repo.findBySku("SKU-1")).isPresent();
    assertThat(repo.existsBySku("SKU-1")).isTrue();
  }

  @Test
  void searchMatchesSkuOrName() {
    repo.save(new Product("KB-100", "Keyboard"));
    repo.save(new Product("MS-200", "Mouse"));
    var page = repo.search("key", org.springframework.data.domain.Pageable.unpaged());
    assertThat(page.getContent()).extracting(Product::getSku).containsExactly("KB-100");
  }
}
```

Run: `./mvnw -q -Dtest=ProductRepositoryIT test`
Expected: FAIL — compilation error (`Product`, `ProductRepository` do not exist)

- [ ] **Step 2: Write entity + repository (minimal to compile the IT)**

`product/Product.java`:

```java
package com.sample.inventory.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "product")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 64)
  private String sku;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private boolean active = true;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public Product(String sku, String name) {
    this.sku = sku;
    this.name = name;
  }

  public void rename(String name) {
    this.name = name;
  }

  public void deactivate() {
    this.active = false;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Product other)) {
      return false;
    }
    return Objects.equals(sku, other.sku);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(sku);
  }
}
```

`product/ProductRepository.java`:

```java
package com.sample.inventory.product;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProductRepository extends JpaRepository<Product, Long> {

  Optional<Product> findBySku(String sku);

  boolean existsBySku(String sku);

  @Query("select p from Product p where lower(p.sku) like lower(concat('%', :q, '%'))"
      + " or lower(p.name) like lower(concat('%', :q, '%'))")
  Page<Product> search(String q, Pageable pageable);
}
```

- [ ] **Step 3: Run IT to verify it passes**

Run: `./mvnw -q -Dtest=ProductRepositoryIT test`
Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 4: Write failing service unit test**

`product/ProductServiceTest.java`:

```java
package com.sample.inventory.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.sample.inventory.common.error.DuplicateException;
import com.sample.inventory.common.error.NotFoundException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

  @Mock ProductRepository repo;
  @InjectMocks ProductService service;

  @Test
  void createsProduct() {
    when(repo.existsBySku("SKU-1")).thenReturn(false);
    when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
    var out = service.create(new ProductRequest("SKU-1", "Keyboard"));
    assertThat(out.sku()).isEqualTo("SKU-1");
  }

  @Test
  void rejectsDuplicateSku() {
    when(repo.existsBySku("SKU-1")).thenReturn(true);
    assertThatThrownBy(() -> service.create(new ProductRequest("SKU-1", "Keyboard")))
        .isInstanceOf(DuplicateException.class);
  }

  @Test
  void getMissingThrowsNotFound() {
    when(repo.findById(9L)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.get(9L)).isInstanceOf(NotFoundException.class);
  }
}
```

Run: `./mvnw -q -Dtest=ProductServiceTest test`
Expected: FAIL — compilation error (`ProductService`, `ProductRequest` do not exist)

- [ ] **Step 5: Write DTOs + mapper + service**

`product/ProductRequest.java`:

```java
package com.sample.inventory.product;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProductRequest(
    @NotBlank @Size(max = 64) String sku, @NotBlank @Size(max = 255) String name) {}
```

`product/UpdateProductRequest.java`:

```java
package com.sample.inventory.product;

import jakarta.validation.constraints.Size;

public record UpdateProductRequest(@Size(max = 255) String name, Boolean active) {}
```

`product/ProductResponse.java`:

```java
package com.sample.inventory.product;

public record ProductResponse(Long id, String sku, String name, boolean active) {}
```

`product/ProductMapper.java`:

```java
package com.sample.inventory.product;

public final class ProductMapper {

  private ProductMapper() {}

  public static ProductResponse toResponse(Product p) {
    return new ProductResponse(p.getId(), p.getSku(), p.getName(), p.isActive());
  }
}
```

`product/ProductService.java`:

```java
package com.sample.inventory.product;

import com.sample.inventory.common.error.DuplicateException;
import com.sample.inventory.common.error.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

  private final ProductRepository repo;

  @Transactional
  public ProductResponse create(ProductRequest req) {
    if (repo.existsBySku(req.sku())) {
      throw new DuplicateException("product", req.sku());
    }
    return ProductMapper.toResponse(repo.save(new Product(req.sku(), req.name())));
  }

  public ProductResponse get(Long id) {
    return repo.findById(id).map(ProductMapper::toResponse)
        .orElseThrow(() -> new NotFoundException("product", id));
  }

  public Page<ProductResponse> search(String q, Pageable pageable) {
    Page<Product> page =
        (q == null || q.isBlank()) ? repo.findAll(pageable) : repo.search(q, pageable);
    return page.map(ProductMapper::toResponse);
  }

  @Transactional
  public ProductResponse update(Long id, UpdateProductRequest req) {
    Product p = repo.findById(id).orElseThrow(() -> new NotFoundException("product", id));
    if (req.name() != null) {
      p.rename(req.name());
    }
    if (Boolean.FALSE.equals(req.active())) {
      p.deactivate();
    }
    return ProductMapper.toResponse(p);
  }
}
```

- [ ] **Step 6: Run service test to verify it passes**

Run: `./mvnw -q -Dtest=ProductServiceTest test`
Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 7: Write controller + controller test**

`product/ProductController.java`:

```java
package com.sample.inventory.product;

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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

  private static final Set<String> SORTABLE = Set.of("sku", "name", "createdAt");
  private static final Sort DEFAULT_SORT = Sort.by("name").ascending();

  private final ProductService service;

  @PostMapping
  public ResponseEntity<ApiResponse<ProductResponse>> create(@Valid @RequestBody ProductRequest req) {
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.create(req)));
  }

  @GetMapping
  public ResponseEntity<ApiResponse<PagedResult<ProductResponse>>> search(
      @RequestParam(required = false) String q, Pageable pageable) {
    var page = service.search(q, SortValidator.validated(pageable, SORTABLE, DEFAULT_SORT));
    return ResponseEntity.ok(ApiResponse.ok(PagedResult.from(page)));
  }

  @GetMapping("/{id}")
  public ResponseEntity<ApiResponse<ProductResponse>> get(@PathVariable Long id) {
    return ResponseEntity.ok(ApiResponse.ok(service.get(id)));
  }

  @PatchMapping("/{id}")
  public ResponseEntity<ApiResponse<ProductResponse>> update(
      @PathVariable Long id, @Valid @RequestBody UpdateProductRequest req) {
    return ResponseEntity.ok(ApiResponse.ok(service.update(id, req)));
  }
}
```

`product/ProductControllerTest.java`:

```java
package com.sample.inventory.product;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProductController.class)
class ProductControllerTest {

  @Autowired MockMvc mvc;
  @MockitoBean ProductService service;

  @Test
  void createReturns201Wrapped() throws Exception {
    when(service.create(any())).thenReturn(new ProductResponse(1L, "SKU-1", "Keyboard", true));
    mvc.perform(post("/api/v1/products")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sku\":\"SKU-1\",\"name\":\"Keyboard\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.sku").value("SKU-1"))
        .andExpect(jsonPath("$.error").isEmpty());
  }

  @Test
  void searchReturnsPagedResult() throws Exception {
    var page = new PageImpl<>(List.of(new ProductResponse(1L, "SKU-1", "Keyboard", true)));
    when(service.search(eq(null), any(Pageable.class))).thenReturn(page);
    mvc.perform(get("/api/v1/products"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].sku").value("SKU-1"))
        .andExpect(jsonPath("$.data.totalElements").value(1));
  }

  @Test
  void invalidBodyReturns400() throws Exception {
    mvc.perform(post("/api/v1/products")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sku\":\"\",\"name\":\"x\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION"));
  }
}
```

Run: `./mvnw -q -Dtest='ProductRepositoryIT,ProductServiceTest,ProductControllerTest' test`
Expected: `Tests run: 8, Failures: 0, Errors: 0`

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/sample/inventory/product src/test/java/com/sample/inventory/product
git commit -m "feat: add product module with crud and paged search"
```

### Task 6: Warehouse module

**Files:**
- Create: `warehouse/Warehouse.java`, `warehouse/WarehouseRepository.java`, `warehouse/WarehouseRequest.java`, `warehouse/WarehouseResponse.java`, `warehouse/WarehouseMapper.java`, `warehouse/WarehouseService.java`, `warehouse/WarehouseController.java`
- Create: `warehouse/WarehouseRepositoryIT.java`, `warehouse/WarehouseServiceTest.java`, `warehouse/WarehouseControllerTest.java`

**Interfaces:**
- Consumes: Task 2 (table `warehouse`), Task 4 (common). `WarehouseService` exposes `create/get/list/update` mirroring `ProductService` signatures with `Warehouse*` DTOs
- Produces: `POST/GET/PATCH /api/v1/warehouses` (GET list = plain `List`, no pagination per spec §6)

- [ ] **Step 1: Write the failing repository IT first**

`src/test/java/com/sample/inventory/warehouse/WarehouseRepositoryIT.java`:

```java
package com.sample.inventory.warehouse;

import static org.assertj.core.api.Assertions.assertThat;

import com.sample.inventory.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Sort;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
class WarehouseRepositoryIT {

  @Autowired WarehouseRepository repo;

  @Test
  void persistsAndEnforcesCodeUniqueness() {
    repo.save(new Warehouse("JKT-1", "Jakarta", 10));
    assertThat(repo.findByCode("JKT-1")).isPresent();
    assertThat(repo.existsByCode("JKT-1")).isTrue();
  }

  @Test
  void findAllOrderedByPriority() {
    repo.save(new Warehouse("B", "B", 20));
    repo.save(new Warehouse("A", "A", 5));
    List<Warehouse> all = repo.findAll(Sort.by("priority").ascending());
    assertThat(all).extracting(Warehouse::getCode).containsExactly("A", "B");
  }
}
```

Run: `./mvnw -q -Dtest=WarehouseRepositoryIT test`
Expected: FAIL — compilation error (`Warehouse`, `WarehouseRepository` do not exist)

- [ ] **Step 2: Write entity + repository**

`warehouse/Warehouse.java`:

```java
package com.sample.inventory.warehouse;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "warehouse")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Warehouse {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 64)
  private String code;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private int priority = 100;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public Warehouse(String code, String name, int priority) {
    this.code = code;
    this.name = name;
    this.priority = priority;
  }

  public void rename(String name) {
    this.name = name;
  }

  public void reprioritize(int priority) {
    this.priority = priority;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Warehouse other)) {
      return false;
    }
    return Objects.equals(code, other.code);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(code);
  }
}
```

`warehouse/WarehouseRepository.java`:

```java
package com.sample.inventory.warehouse;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarehouseRepository extends JpaRepository<Warehouse, Long> {

  Optional<Warehouse> findByCode(String code);

  boolean existsByCode(String code);
}
```

- [ ] **Step 3: Run IT to verify it passes**

Run: `./mvnw -q -Dtest=WarehouseRepositoryIT test`
Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 4: Write failing service unit test**

`warehouse/WarehouseServiceTest.java`:

```java
package com.sample.inventory.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.sample.inventory.common.error.DuplicateException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class WarehouseServiceTest {

  @Mock WarehouseRepository repo;
  @InjectMocks WarehouseService service;

  @Test
  void createsWarehouse() {
    when(repo.existsByCode("JKT-1")).thenReturn(false);
    when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
    var out = service.create(new WarehouseRequest("JKT-1", "Jakarta", 10));
    assertThat(out.code()).isEqualTo("JKT-1");
  }

  @Test
  void rejectsDuplicateCode() {
    when(repo.existsByCode("JKT-1")).thenReturn(true);
    assertThatThrownBy(() -> service.create(new WarehouseRequest("JKT-1", "Jakarta", 10)))
        .isInstanceOf(DuplicateException.class);
  }

  @Test
  void listReturnsPriorityOrdered() {
    var w = new Warehouse("A", "A", 5);
    when(repo.findAll(Sort.by("priority").ascending())).thenReturn(List.of(w));
    assertThat(service.list()).extracting(WarehouseResponse::code).containsExactly("A");
  }

  @Test
  void getMissingThrows() {
    when(repo.findById(9L)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.get(9L))
        .isInstanceOf(com.sample.inventory.common.error.NotFoundException.class);
  }
}
```

Run: `./mvnw -q -Dtest=WarehouseServiceTest test`
Expected: FAIL — compilation error (`WarehouseService`, `WarehouseRequest`, `WarehouseResponse` do not exist)

- [ ] **Step 5: Write DTOs + mapper + service**

`warehouse/WarehouseRequest.java`:

```java
package com.sample.inventory.warehouse;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WarehouseRequest(
    @NotBlank @Size(max = 64) String code,
    @NotBlank @Size(max = 255) String name,
    @Min(0) int priority) {}
```

`warehouse/UpdateWarehouseRequest.java`:

```java
package com.sample.inventory.warehouse;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record UpdateWarehouseRequest(@Size(max = 255) String name, @Min(0) Integer priority) {}
```

`warehouse/WarehouseResponse.java`:

```java
package com.sample.inventory.warehouse;

public record WarehouseResponse(Long id, String code, String name, int priority) {}
```

`warehouse/WarehouseMapper.java`:

```java
package com.sample.inventory.warehouse;

public final class WarehouseMapper {

  private WarehouseMapper() {}

  public static WarehouseResponse toResponse(Warehouse w) {
    return new WarehouseResponse(w.getId(), w.getCode(), w.getName(), w.getPriority());
  }
}
```

`warehouse/WarehouseService.java`:

```java
package com.sample.inventory.warehouse;

import com.sample.inventory.common.error.DuplicateException;
import com.sample.inventory.common.error.NotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WarehouseService {

  private final WarehouseRepository repo;

  @Transactional
  public WarehouseResponse create(WarehouseRequest req) {
    if (repo.existsByCode(req.code())) {
      throw new DuplicateException("warehouse", req.code());
    }
    return WarehouseMapper.toResponse(repo.save(new Warehouse(req.code(), req.name(), req.priority())));
  }

  public WarehouseResponse get(Long id) {
    return repo.findById(id).map(WarehouseMapper::toResponse)
        .orElseThrow(() -> new NotFoundException("warehouse", id));
  }

  public List<WarehouseResponse> list() {
    return repo.findAll(Sort.by("priority").ascending()).stream()
        .map(WarehouseMapper::toResponse)
        .toList();
  }

  @Transactional
  public WarehouseResponse update(Long id, UpdateWarehouseRequest req) {
    Warehouse w = repo.findById(id).orElseThrow(() -> new NotFoundException("warehouse", id));
    if (req.name() != null) {
      w.rename(req.name());
    }
    if (req.priority() != null) {
      w.reprioritize(req.priority());
    }
    return WarehouseMapper.toResponse(w);
  }
}
```

- [ ] **Step 6: Run service test to verify it passes**

Run: `./mvnw -q -Dtest=WarehouseServiceTest test`
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 7: Write controller + controller test**

`warehouse/WarehouseController.java`:

```java
package com.sample.inventory.warehouse;

import com.sample.inventory.common.web.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/warehouses")
@RequiredArgsConstructor
public class WarehouseController {

  private final WarehouseService service;

  @PostMapping
  public ResponseEntity<ApiResponse<WarehouseResponse>> create(
      @Valid @RequestBody WarehouseRequest req) {
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.create(req)));
  }

  @GetMapping
  public ResponseEntity<ApiResponse<List<WarehouseResponse>>> list() {
    return ResponseEntity.ok(ApiResponse.ok(service.list()));
  }

  @GetMapping("/{id}")
  public ResponseEntity<ApiResponse<WarehouseResponse>> get(@PathVariable Long id) {
    return ResponseEntity.ok(ApiResponse.ok(service.get(id)));
  }

  @PatchMapping("/{id}")
  public ResponseEntity<ApiResponse<WarehouseResponse>> update(
      @PathVariable Long id, @Valid @RequestBody UpdateWarehouseRequest req) {
    return ResponseEntity.ok(ApiResponse.ok(service.update(id, req)));
  }
}
```

`warehouse/WarehouseControllerTest.java`:

```java
package com.sample.inventory.warehouse;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WarehouseController.class)
class WarehouseControllerTest {

  @Autowired MockMvc mvc;
  @MockitoBean WarehouseService service;

  @Test
  void createReturns201Wrapped() throws Exception {
    when(service.create(any())).thenReturn(new WarehouseResponse(1L, "JKT-1", "Jakarta", 10));
    mvc.perform(post("/api/v1/warehouses")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"JKT-1\",\"name\":\"Jakarta\",\"priority\":10}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.code").value("JKT-1"));
  }

  @Test
  void listReturnsPlainArray() throws Exception {
    when(service.list()).thenReturn(List.of(new WarehouseResponse(1L, "JKT-1", "Jakarta", 10)));
    mvc.perform(get("/api/v1/warehouses"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].priority").value(10));
  }
}
```

Run: `./mvnw -q -Dtest='WarehouseRepositoryIT,WarehouseServiceTest,WarehouseControllerTest' test`
Expected: `Tests run: 8, Failures: 0, Errors: 0`

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/sample/inventory/warehouse src/test/java/com/sample/inventory/warehouse
git commit -m "feat: add warehouse module with priority ordering"
```

### Task 7: Format, Swagger, seed, demo

**Files:**
- Create: `common/dev/DevSeeder.java`
- Modify: `README.md` (endpoint table)

**Interfaces:**
- Consumes: Tasks 1–6
- Produces: `./mvnw verify` green incl. Spotless; Swagger UI live; dev seed; README endpoint table

- [ ] **Step 1: Write dev seeder**

`common/dev/DevSeeder.java`:

```java
package com.sample.inventory.common.dev;

import com.sample.inventory.common.error.DuplicateException;
import com.sample.inventory.product.ProductRequest;
import com.sample.inventory.product.ProductService;
import com.sample.inventory.warehouse.WarehouseRequest;
import com.sample.inventory.warehouse.WarehouseService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("dev")
@RequiredArgsConstructor
public class DevSeeder implements ApplicationRunner {

  private final ProductService products;
  private final WarehouseService warehouses;

  @Override
  public void run(ApplicationArguments args) {
    // Idempotent: restart aman meski DB dev sudah berisi seed
    seedWarehouse("JKT-1", "Jakarta", 10);
    seedWarehouse("SBY-1", "Surabaya", 20);
    try {
      products.create(new ProductRequest("KB-100", "Keyboard"));
    } catch (DuplicateException ignored) {
      // already seeded
    }
  }

  private void seedWarehouse(String code, String name, int priority) {
    try {
      warehouses.create(new WarehouseRequest(code, name, priority));
    } catch (DuplicateException ignored) {
      // already seeded
    }
  }
}
```

- [ ] **Step 2: Format + full verify**

Run: `./mvnw spotless:apply`
Expected: `BUILD SUCCESS`, files reformatted if needed

Run: `./mvnw verify`
Expected: `BUILD SUCCESS`, all tests pass, `spotless:check` passes (bound to default `verify` phase via executions block)

- [ ] **Step 3: Live demo via Swagger + curl**

Run: `SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run` (new terminal), then:

Run: `curl -s http://localhost:8080/v3/api-docs | head -c 300`
Expected: JSON starting with `{"openapi":"3`

Run:

```bash
curl -s http://localhost:8080/api/v1/products | head -c 300
curl -s http://localhost:8080/api/v1/warehouses | head -c 300
```

Expected: seeded `KB-100` product and `JKT-1, SBY-1` warehouses in `data`
Open `http://localhost:8080/swagger-ui.html` in browser and confirm both controllers listed

- [ ] **Step 4: Update README endpoint table + commit**

Append under a `## API (Fase 1a)` heading:

```markdown
## API (Fase 1a)

| Method | Path | Keterangan |
|---|---|---|
| POST | /api/v1/products | create (201), duplicate sku → 409 |
| GET | /api/v1/products?q=&page=&size=&sort= | paged, sort whitelist: sku,name,createdAt |
| GET / PATCH | /api/v1/products/{id} | detail / rename + active flag |
| POST | /api/v1/warehouses | create (201) |
| GET | /api/v1/warehouses | list polos by priority |
| GET / PATCH | /api/v1/warehouses/{id} | detail / rename + reprioritize |

Swagger UI: /swagger-ui.html — OpenAPI: /v3/api-docs
```

```bash
git add src/main/java/com/sample/inventory/common/dev README.md
git commit -m "feat: add dev seeder and fase 1a readme"
```

## Plan Complete — Fase 1b Preview

Next plan (separate file): inventory read + order slice (create/split/confirm/cancel/expiry worker + movement writes + concurrency IT + negative-amount guards).
