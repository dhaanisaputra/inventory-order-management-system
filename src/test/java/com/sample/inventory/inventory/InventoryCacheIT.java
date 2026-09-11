package com.sample.inventory.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.sample.inventory.TestcontainersConfiguration;
import com.sample.inventory.movement.StockMovementRepository;
import com.sample.inventory.product.Product;
import com.sample.inventory.product.ProductRepository;
import com.sample.inventory.purchase.CreatePurchaseRequest;
import com.sample.inventory.purchase.CreatePurchaseRequest.CreatePurchaseLine;
import com.sample.inventory.purchase.PurchaseOrderRepository;
import com.sample.inventory.purchase.PurchaseService;
import com.sample.inventory.purchase.ReceiveRequest;
import com.sample.inventory.purchase.ReceiveRequest.ReceiveItem;
import com.sample.inventory.warehouse.Warehouse;
import com.sample.inventory.warehouse.WarehouseRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class InventoryCacheIT {

  @Autowired InventoryService inventories;
  @Autowired PurchaseService purchases;
  @Autowired CacheManager cacheManager;
  @Autowired InventoryRepository invRepo;
  @Autowired PurchaseOrderRepository poRepo;
  @Autowired ProductRepository productRepo;
  @Autowired WarehouseRepository warehouseRepo;
  @Autowired StockMovementRepository movementRepo;

  Product p;
  Warehouse w;

  @BeforeEach
  void seed() {
    movementRepo.deleteAll();
    poRepo.deleteAll();
    invRepo.deleteAll();
    p = productRepo.save(new Product("SKU-" + System.nanoTime(), "Keyboard"));
    w = warehouseRepo.save(new Warehouse("W-" + System.nanoTime(), "W", 1));
    invRepo.save(new Inventory(p, w, 0, 0, 5));
  }

  @Test
  void mutationEvictsCachedProductStock() {
    var before = inventories.getByProduct(p.getId());
    assertThat(before.get(0).available()).isZero();
    assertThat(cacheManager.getCache("inv").get(p.getId())).isNotNull();
    var po =
        purchases.create(new CreatePurchaseRequest(List.of(new CreatePurchaseLine(p.getId(), 7))));
    purchases.receive(
        po.id(), new ReceiveRequest(List.of(new ReceiveItem(p.getId(), w.getId(), 7))));
    var after = inventories.getByProduct(p.getId());
    assertThat(after.get(0).available()).isEqualTo(7);
  }
}
