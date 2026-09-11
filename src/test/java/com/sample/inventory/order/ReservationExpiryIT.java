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
import java.time.Instant;
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
  @Autowired OrderIdempotencyRepository idemRepo;
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
    idemRepo.deleteAll();
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
    int n = expiry.expireBatch(Instant.now());
    assertThat(n).isEqualTo(1);
    var inv = invRepo.findByProduct_Id(p.getId()).get(0);
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
    int n = expiry.expireBatch(Instant.now());
    assertThat(n).isZero();
  }
}
