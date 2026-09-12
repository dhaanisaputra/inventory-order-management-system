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
import com.sample.inventory.order.OrderIdempotencyRepository;
import com.sample.inventory.order.OrderService;
import com.sample.inventory.order.ReservationRepository;
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
  @Autowired ReservationRepository reservationRepo;
  @Autowired OrderIdempotencyRepository idemRepo;
  @Autowired InventoryRepository invRepo;
  @Autowired ProductRepository productRepo;
  @Autowired WarehouseRepository warehouseRepo;
  @Autowired StockMovementRepository movementRepo;

  Product p;

  @BeforeEach
  void seed() {
    movementRepo.deleteAll();
    reservationRepo.deleteAll();
    idemRepo.deleteAll();
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
    // avg=10/30 per day; lead 7d + safety 3d → reorderPoint=10/30*10=3.3333; available=12-10=2 → suggest 2
    var all = replenishment.suggestAll();
    var mine = all.stream().filter(s -> s.productSku().equals(p.getSku())).toList();
    assertThat(mine).hasSize(1);
    var s = mine.get(0);
    assertThat(s.available()).isEqualTo(2);
    assertThat(s.reorderPoint()).isCloseTo(10.0 / 30.0 * 10.0, within(0.0001));
    assertThat(s.suggestedQty()).isEqualTo(2);
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
