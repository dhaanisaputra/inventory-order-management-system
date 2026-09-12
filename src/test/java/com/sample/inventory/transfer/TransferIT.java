package com.sample.inventory.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sample.inventory.TestcontainersConfiguration;
import com.sample.inventory.common.error.DomainException;
import com.sample.inventory.common.error.ErrorCode;
import com.sample.inventory.common.error.InsufficientStockException;
import com.sample.inventory.inventory.Inventory;
import com.sample.inventory.inventory.InventoryRepository;
import com.sample.inventory.movement.MovementType;
import com.sample.inventory.movement.StockMovementRepository;
import com.sample.inventory.product.Product;
import com.sample.inventory.product.ProductRepository;
import com.sample.inventory.warehouse.Warehouse;
import com.sample.inventory.warehouse.WarehouseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TransferIT {

  @Autowired TransferService transfers;
  @Autowired TransferRepository transferRepo;
  @Autowired InventoryRepository invRepo;
  @Autowired ProductRepository productRepo;
  @Autowired WarehouseRepository warehouseRepo;
  @Autowired StockMovementRepository movementRepo;

  Product p;
  Warehouse src;
  Warehouse dst;

  @BeforeEach
  void seed() {
    movementRepo.deleteAll();
    transferRepo.deleteAll();
    invRepo.deleteAll();
    p = productRepo.save(new Product("SKU-" + System.nanoTime(), "Keyboard"));
    src = warehouseRepo.save(new Warehouse("S-" + System.nanoTime(), "Src", 10));
    dst = warehouseRepo.save(new Warehouse("D-" + System.nanoTime(), "Dst", 20));
    invRepo.save(new Inventory(p, src, 10, 0, 2));
  }

  @Test
  void transferMovesStockWithDoubleEntry() {
    var res = transfers.create(new TransferRequest(p.getId(), src.getId(), dst.getId(), 4));
    assertThat(res.qty()).isEqualTo(4);
    assertThat(res.fromWarehouseCode()).isEqualTo(src.getCode());
    assertThat(res.toWarehouseCode()).isEqualTo(dst.getCode());
    var rows = invRepo.findByProduct_Id(p.getId());
    assertThat(rows).extracting(i -> i.getWarehouse().getCode() + "=" + i.getAvailable())
        .containsExactlyInAnyOrder(src.getCode() + "=6", dst.getCode() + "=4");
    var moves = movementRepo.findAll();
    assertThat(moves).extracting(m -> m.getType())
        .containsExactlyInAnyOrder(MovementType.OUT, MovementType.IN);
    assertThat(moves).extracting(m -> m.getRefId()).containsOnly(res.id());
    assertThat(moves).extracting(m -> m.getRefType()).containsOnly("TRANSFER");
  }

  @Test
  void transferToNewWarehouseCreatesRow() {
    var res = transfers.create(new TransferRequest(p.getId(), src.getId(), dst.getId(), 10));
    assertThat(res.qty()).isEqualTo(10);
    var inv = invRepo.findByProduct_Id(p.getId());
    assertThat(inv).extracting(i -> i.getAvailable()).containsExactlyInAnyOrder(0, 10);
  }

  @Test
  void insufficientSourceIsRejected() {
    assertThatThrownBy(() -> transfers.create(
        new TransferRequest(p.getId(), src.getId(), dst.getId(), 99)))
        .isInstanceOf(InsufficientStockException.class);
    assertThat(transferRepo.count()).isZero();
    assertThat(movementRepo.count()).isZero();
    assertThat(invRepo.findByProduct_Id(p.getId()).get(0).getAvailable()).isEqualTo(10);
  }

  @Test
  void selfTransferIsRejected() {
    assertThatThrownBy(() -> transfers.create(
        new TransferRequest(p.getId(), src.getId(), src.getId(), 1)))
        .isInstanceOf(DomainException.class)
        .satisfies(e -> assertThat(((DomainException) e).getCode())
            .isEqualTo(ErrorCode.VALIDATION));
  }
}
