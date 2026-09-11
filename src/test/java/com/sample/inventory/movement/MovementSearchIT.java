package com.sample.inventory.movement;

import static org.assertj.core.api.Assertions.assertThat;

import com.sample.inventory.TestcontainersConfiguration;
import com.sample.inventory.product.Product;
import com.sample.inventory.product.ProductRepository;
import com.sample.inventory.warehouse.Warehouse;
import com.sample.inventory.warehouse.WarehouseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MovementSearchIT {

  @Autowired MovementService service;
  @Autowired StockMovementRepository movements;
  @Autowired ProductRepository products;
  @Autowired WarehouseRepository warehouses;

  @BeforeEach
  void clean() {
    movements.deleteAll();
  }

  @Test
  void searchWithAllNullsDoesNot500() {
    var p = products.save(new Product("SKU-" + System.nanoTime(), "Keyboard"));
    var w = warehouses.save(new Warehouse("W-" + System.nanoTime(), "W", 1));
    movements.save(StockMovement.of(p, w, MovementType.RESERVE, 2, "ORDER", 1L));
    var page = service.search(null, null, null, null, null, Pageable.unpaged());
    assertThat(page.getTotalElements()).isEqualTo(1);
  }
}
