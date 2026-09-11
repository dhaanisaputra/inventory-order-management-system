package com.sample.inventory.purchase;

import static org.assertj.core.api.Assertions.assertThat;

import com.sample.inventory.TestcontainersConfiguration;
import com.sample.inventory.inventory.Inventory;
import com.sample.inventory.inventory.InventoryRepository;
import com.sample.inventory.movement.StockMovementRepository;
import com.sample.inventory.product.Product;
import com.sample.inventory.product.ProductRepository;
import com.sample.inventory.purchase.CreatePurchaseRequest.CreatePurchaseLine;
import com.sample.inventory.purchase.ReceiveRequest.ReceiveItem;
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
class PurchaseConcurrencyIT {

  @Autowired PurchaseService purchases;
  @Autowired PurchaseOrderRepository poRepo;
  @Autowired InventoryRepository invRepo;
  @Autowired ProductRepository productRepo;
  @Autowired WarehouseRepository warehouseRepo;
  @Autowired StockMovementRepository movementRepo;

  Product p;
  Warehouse w;
  long poId;

  @BeforeEach
  void seed() {
    movementRepo.deleteAll();
    poRepo.deleteAll();
    invRepo.deleteAll();
    p = productRepo.save(new Product("SKU-" + System.nanoTime(), "Keyboard"));
    w = warehouseRepo.save(new Warehouse("W-" + System.nanoTime(), "W", 1));
    invRepo.save(new Inventory(p, w, 0, 0, 5));
    poId =
        purchases
            .create(new CreatePurchaseRequest(List.of(new CreatePurchaseLine(p.getId(), 5))))
            .id();
  }

  @Test
  void concurrentFullReceivesHaveExactlyOneWinner() throws Exception {
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
    var inv = invRepo.findByProduct_Id(p.getId()).get(0);
    assertThat(inv.getAvailable()).isEqualTo(5);
  }

  private void attempt(
      CountDownLatch gate, CountDownLatch done, AtomicInteger wins, AtomicInteger fails) {
    try {
      gate.await();
      purchases.receive(
          poId, new ReceiveRequest(List.of(new ReceiveItem(p.getId(), w.getId(), 5))));
      wins.incrementAndGet();
    } catch (Exception e) {
      fails.incrementAndGet();
    } finally {
      done.countDown();
    }
  }
}
