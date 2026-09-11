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
    assertThat(got).extracting(i -> i.getWarehouse().getCode()).containsExactly("HIGH", "LOW");
  }

  @Test
  void lockAvailableSkipsEmptyStock() {
    var p = productRepo.save(new Product("SKU-2", "Mouse"));
    var w = warehouseRepo.save(new Warehouse("W", "W", 1));
    invRepo.save(new Inventory(p, w, 0, 0, 0));
    assertThat(invRepo.lockAvailable(p.getId())).isEmpty();
  }
}
