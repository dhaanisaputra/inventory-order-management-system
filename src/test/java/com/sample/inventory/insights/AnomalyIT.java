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
import com.sample.inventory.order.CreateOrderRequest;
import com.sample.inventory.order.CreateOrderRequest.CreateOrderLine;
import com.sample.inventory.order.OrderIdempotencyRepository;
import com.sample.inventory.order.OrderService;
import com.sample.inventory.order.ReservationExpiryService;
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
class AnomalyIT {

  @Autowired AnomalyService anomalies;
  @Autowired StockMovementRepository movementRepo;
  @Autowired SalesOrderRepository orderRepo;
  @Autowired InventoryRepository invRepo;
  @Autowired ProductRepository productRepo;
  @Autowired WarehouseRepository warehouseRepo;
  @Autowired OrderService orders;
  @Autowired ReservationExpiryService expiry;
  @Autowired OrderIdempotencyRepository idemRepo;
  @Autowired ReservationRepository reservationRepo;

  Product p;
  Warehouse w;

  @BeforeEach
  void seed() {
    movementRepo.deleteAll();
    reservationRepo.deleteAll();
    idemRepo.deleteAll();
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
    assertThat(found)
        .filteredOn(a -> a.type() == AnomalyType.SPIKE && a.productId().equals(p.getId()))
        .hasSize(1);
  }

  @Test
  void quietDataProducesNothing() {
    movementRepo.save(StockMovement.of(p, w, MovementType.OUT, 2, "ORDER", 1));
    var found = anomalies.scan(30);
    assertThat(found)
        .filteredOn(a -> a.productId() != null && a.productId().equals(p.getId()))
        .isEmpty();
  }

  @Test
  void detectsReturnAbuse() {
    for (int i = 0; i < 4; i++) {
      movementRepo.save(StockMovement.of(p, w, MovementType.OUT, 2, "ORDER", 300 + i));
    }
    movementRepo.save(StockMovement.of(p, w, MovementType.IN, 10, "RETURN", 400));
    var found = anomalies.scan(30);
    assertThat(found)
        .filteredOn(a -> a.type() == AnomalyType.HIGH_RETURNS && a.productId().equals(p.getId()))
        .hasSize(1);
  }

  @Test
  void detectsHighExpiryRate() {
    for (int i = 0; i < 6; i++) {
      orders.create(new CreateOrderRequest(List.of(new CreateOrderLine(p.getId(), 1))), null, null);
    }
    expiry.expireBatch(java.time.Instant.now().plusSeconds(3600));
    var found = anomalies.scan(30);
    assertThat(found).filteredOn(a -> a.type() == AnomalyType.HIGH_EXPIRY).hasSize(1);
  }
}
