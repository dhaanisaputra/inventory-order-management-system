package com.sample.inventory.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sample.inventory.TestcontainersConfiguration;
import com.sample.inventory.common.error.InsufficientStockException;
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

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OrderCreateIT {

  @Autowired OrderService orders;
  @Autowired SalesOrderRepository orderRepo;
  @Autowired OrderIdempotencyRepository idemRepo;
  @Autowired ReservationRepository reservationRepo;
  @Autowired InventoryRepository invRepo;
  @Autowired ProductRepository productRepo;
  @Autowired WarehouseRepository warehouseRepo;
  @Autowired StockMovementRepository movementRepo;

  Product p;
  Warehouse high;
  Warehouse low;

  @BeforeEach
  void seed() {
    movementRepo.deleteAll();
    reservationRepo.deleteAll();
    idemRepo.deleteAll();
    orderRepo.deleteAll();
    invRepo.deleteAll();
    p = productRepo.save(new Product("SKU-" + System.nanoTime(), "Keyboard"));
    high = warehouseRepo.save(new Warehouse("H-" + System.nanoTime(), "High", 5));
    low = warehouseRepo.save(new Warehouse("L-" + System.nanoTime(), "Low", 50));
    invRepo.save(new Inventory(p, high, 3, 0, 1));
    invRepo.save(new Inventory(p, low, 10, 0, 1));
  }

  @Test
  void splitsByPriorityAndReserves() {
    var res = orders.create(new CreateOrderRequest(List.of(new CreateOrderLine(p.getId(), 5))), null, null);
    assertThat(res.status()).isEqualTo(OrderStatus.PENDING);
    assertThat(res.lines()).hasSize(1);
    var allocs = res.lines().get(0).allocations();
    assertThat(allocs).hasSize(2);
    assertThat(allocs.get(0).warehouseCode()).isEqualTo(high.getCode());
    assertThat(allocs.get(0).qty()).isEqualTo(3);
    assertThat(allocs.get(1).warehouseCode()).isEqualTo(low.getCode());
    assertThat(allocs.get(1).qty()).isEqualTo(2);
    var inv = invRepo.findByProduct_Id(p.getId());
    assertThat(inv).extracting(i -> i.getAvailable() + i.getReserved())
        .containsExactlyInAnyOrder(3, 10);
    assertThat(movementRepo.count()).isEqualTo(2);
    assertThat(movementRepo.findAll()).extracting(m -> m.getType())
        .containsOnly(MovementType.RESERVE);
  }

  @Test
  void insufficientStockRollsBackFully() {
    assertThatThrownBy(() -> orders.create(
        new CreateOrderRequest(List.of(new CreateOrderLine(p.getId(), 99))), null, null))
        .isInstanceOf(InsufficientStockException.class);
    assertThat(orderRepo.count()).isZero();
    assertThat(movementRepo.count()).isZero();
    assertThat(invRepo.findByProduct_Id(p.getId()))
        .extracting(i -> i.getReserved()).containsOnly(0);
  }

  @Test
  void idempotencyKeyReplaysSameOrder() {
    var req = new CreateOrderRequest(List.of(new CreateOrderLine(p.getId(), 2)));
    var first = orders.create(req, "key-1", RequestHash.of(req));
    var second = orders.create(req, "key-1", RequestHash.of(req));
    assertThat(second.id()).isEqualTo(first.id());
    assertThat(orderRepo.count()).isEqualTo(1);
  }
}
