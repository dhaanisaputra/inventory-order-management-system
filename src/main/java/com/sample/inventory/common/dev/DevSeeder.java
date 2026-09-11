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
