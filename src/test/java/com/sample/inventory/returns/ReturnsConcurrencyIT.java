package com.sample.inventory.returns;

import static org.assertj.core.api.Assertions.assertThat;

import com.sample.inventory.TestcontainersConfiguration;
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
import com.sample.inventory.returns.CreateReturnRequest.CreateReturnLine;
import com.sample.inventory.warehouse.Warehouse;
import com.sample.inventory.warehouse.WarehouseRepository;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReturnsConcurrencyIT {

  @Autowired ReturnService returns;
  @Autowired ReturnOrderRepository returnRepo;
  @Autowired OrderService orders;
  @Autowired SalesOrderRepository orderRepo;
  @Autowired ReservationRepository reservationRepo;
  @Autowired OrderIdempotencyRepository idemRepo;
  @Autowired InventoryRepository invRepo;
  @Autowired ProductRepository productRepo;
  @Autowired WarehouseRepository warehouseRepo;
  @Autowired StockMovementRepository movementRepo;

  long allocationId;

  @BeforeEach
  void seed() {
    movementRepo.deleteAll();
    returnRepo.deleteAll();
    reservationRepo.deleteAll();
    idemRepo.deleteAll();
    orderRepo.deleteAll();
    invRepo.deleteAll();
    var p = productRepo.save(new Product("SKU-" + System.nanoTime(), "Keyboard"));
    var w = warehouseRepo.save(new Warehouse("W-" + System.nanoTime(), "W", 1));
    invRepo.save(new Inventory(p, w, 5, 0, 1));
    var order =
        orders.create(
            new CreateOrderRequest(List.of(new CreateOrderLine(p.getId(), 3))), null, null);
    allocationId = order.lines().get(0).allocations().get(0).id();
  }

  @Test
  void concurrentReturnsRespectTheCap() throws Exception {
    var gate = new CountDownLatch(1);
    var done = new CountDownLatch(2);
    var wins = new AtomicInteger();
    var fails = new AtomicInteger();
    try (var pool = Executors.newFixedThreadPool(2)) {
      List<Future<?>> futures =
          List.of(
              pool.submit(() -> attempt(gate, done, wins, fails)),
              pool.submit(() -> attempt(gate, done, wins, fails)));
      gate.countDown();
      done.await();
      for (var f : futures) {
        f.get();
      }
    }
    assertThat(wins.get()).isEqualTo(1);
    assertThat(fails.get()).isEqualTo(1);
  }

  private void attempt(
      CountDownLatch gate, CountDownLatch done, AtomicInteger wins, AtomicInteger fails) {
    try {
      gate.await();
      returns.create(new CreateReturnRequest(List.of(new CreateReturnLine(allocationId, 2))));
      wins.incrementAndGet();
    } catch (Exception e) {
      fails.incrementAndGet();
    } finally {
      done.countDown();
    }
  }
}
