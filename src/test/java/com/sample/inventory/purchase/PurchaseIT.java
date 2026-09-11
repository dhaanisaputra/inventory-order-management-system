package com.sample.inventory.purchase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sample.inventory.TestcontainersConfiguration;
import com.sample.inventory.common.error.OverReceiveException;
import com.sample.inventory.common.error.NotFoundException;
import com.sample.inventory.inventory.Inventory;
import com.sample.inventory.inventory.InventoryRepository;
import com.sample.inventory.movement.MovementType;
import com.sample.inventory.movement.StockMovementRepository;
import com.sample.inventory.product.Product;
import com.sample.inventory.product.ProductRepository;
import com.sample.inventory.purchase.CreatePurchaseRequest.CreatePurchaseLine;
import com.sample.inventory.purchase.ReceiveRequest.ReceiveItem;
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
class PurchaseIT {

  @Autowired PurchaseService purchases;
  @Autowired PurchaseOrderRepository poRepo;
  @Autowired InventoryRepository invRepo;
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
  void partialThenCompleteReceiving() {
    var po = purchases.create(new CreatePurchaseRequest(
        List.of(new CreatePurchaseLine(p.getId(), 10))));
    assertThat(po.status()).isEqualTo(PurchaseOrderStatus.OPEN);
    var afterFirst = purchases.receive(po.id(),
        new ReceiveRequest(List.of(new ReceiveItem(p.getId(), w.getId(), 4))));
    assertThat(afterFirst.status()).isEqualTo(PurchaseOrderStatus.OPEN);
    var afterSecond = purchases.receive(po.id(),
        new ReceiveRequest(List.of(new ReceiveItem(p.getId(), w.getId(), 6))));
    assertThat(afterSecond.status()).isEqualTo(PurchaseOrderStatus.COMPLETED);
    var inv = invRepo.findByProduct_Id(p.getId()).get(0);
    assertThat(inv.getAvailable()).isEqualTo(10);
    assertThat(movementRepo.findAll()).extracting(m -> m.getType())
        .containsOnly(MovementType.IN);
  }

  @Test
  void overReceiveIsRejected() {
    var po = purchases.create(new CreatePurchaseRequest(
        List.of(new CreatePurchaseLine(p.getId(), 5))));
    assertThatThrownBy(() -> purchases.receive(po.id(),
        new ReceiveRequest(List.of(new ReceiveItem(p.getId(), w.getId(), 6)))))
        .isInstanceOf(OverReceiveException.class);
    var inv = invRepo.findByProduct_Id(p.getId()).get(0);
    assertThat(inv.getAvailable()).isZero();
  }

  @Test
  void unknownProductLineIsRejected() {
    var po = purchases.create(new CreatePurchaseRequest(
        List.of(new CreatePurchaseLine(p.getId(), 5))));
    assertThatThrownBy(() -> purchases.receive(po.id(),
        new ReceiveRequest(List.of(new ReceiveItem(999999L, w.getId(), 1)))))
        .isInstanceOf(NotFoundException.class);
  }
}
