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
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class OrderConfirmCancelIT {

  @Autowired OrderService orders;
  @Autowired SalesOrderRepository orderRepo;
  @Autowired ReservationRepository reservationRepo;
  @Autowired OrderIdempotencyRepository idemRepo;
  @Autowired InventoryRepository invRepo;
  @Autowired ProductRepository productRepo;
  @Autowired WarehouseRepository warehouseRepo;
  @Autowired StockMovementRepository movementRepo;
  @Autowired ReservationExpiryService expiry;

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

  private OrderResponse pendingOrder(int qty) {
    return orders.create(
        new CreateOrderRequest(List.of(new CreateOrderLine(p.getId(), qty))), null, null);
  }

  @Test
  void confirmDecrementsReservedAndWritesOut() {
    var created = pendingOrder(2);
    var confirmed = orders.confirm(created.id());
    assertThat(confirmed.status()).isEqualTo(OrderStatus.CONFIRMED);
    var inv = invRepo.lockOne(p.getId(), w.getId()).orElseThrow();
    assertThat(inv.getAvailable()).isEqualTo(3);
    assertThat(inv.getReserved()).isEqualTo(0);
    assertThat(movementRepo.findAll())
        .extracting(m -> m.getType())
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
    assertThat(movementRepo.findAll())
        .extracting(m -> m.getType())
        .containsExactlyInAnyOrder(MovementType.RESERVE, MovementType.RELEASE);
  }

  @Test
  void confirmAfterCancelIsInvalid() {
    var created = pendingOrder(2);
    orders.cancel(created.id());
    assertThatThrownBy(() -> orders.confirm(created.id()))
        .isInstanceOf(InvalidTransitionException.class);
  }

  @Test
  void confirmAfterExpiryIsInvalid() {
    var created = pendingOrder(2);
    expiry.expireBatch(java.time.Instant.now().plusSeconds(3600));
    assertThatThrownBy(() -> orders.confirm(created.id()))
        .isInstanceOf(InvalidTransitionException.class);
  }
}
