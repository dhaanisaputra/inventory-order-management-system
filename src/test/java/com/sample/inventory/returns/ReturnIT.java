package com.sample.inventory.returns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sample.inventory.TestcontainersConfiguration;
import com.sample.inventory.common.error.DomainException;
import com.sample.inventory.common.error.ReturnExceededException;
import com.sample.inventory.inventory.Inventory;
import com.sample.inventory.inventory.InventoryRepository;
import com.sample.inventory.movement.MovementType;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReturnIT {

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

  Product p;
  Warehouse w;
  long allocationId;

  @BeforeEach
  void seed() {
    movementRepo.deleteAll();
    returnRepo.deleteAll();
    reservationRepo.deleteAll();
    idemRepo.deleteAll();
    orderRepo.deleteAll();
    invRepo.deleteAll();
    p = productRepo.save(new Product("SKU-" + System.nanoTime(), "Keyboard"));
    w = warehouseRepo.save(new Warehouse("W-" + System.nanoTime(), "W", 1));
    invRepo.save(new Inventory(p, w, 5, 0, 1));
    var order = orders.create(
        new CreateOrderRequest(List.of(new CreateOrderLine(p.getId(), 3))), null, null);
    allocationId = order.lines().get(0).allocations().get(0).id();
  }

  @Test
  void returnRestocksOriginWarehouse() {
    var ret = returns.create(new CreateReturnRequest(
        List.of(new CreateReturnLine(allocationId, 2))));
    assertThat(ret.lines()).hasSize(1);
    assertThat(ret.lines().get(0).warehouseCode()).isEqualTo(w.getCode());
    var inv = invRepo.findByProduct_Id(p.getId()).get(0);
    assertThat(inv.getAvailable()).isEqualTo(4);
    assertThat(inv.getReserved()).isEqualTo(3);
    assertThat(movementRepo.findAll()).extracting(m -> m.getType())
        .containsExactlyInAnyOrder(MovementType.RESERVE, MovementType.IN);
  }

  @Test
  void returnBeyondAllocatedIsRejected() {
    assertThatThrownBy(() -> returns.create(new CreateReturnRequest(
        List.of(new CreateReturnLine(allocationId, 4)))))
        .isInstanceOf(ReturnExceededException.class);
    assertThat(returnRepo.count()).isZero();
  }

  @Test
  void cumulativeReturnsAreCapped() {
    returns.create(new CreateReturnRequest(List.of(new CreateReturnLine(allocationId, 2))));
    assertThatThrownBy(() -> returns.create(new CreateReturnRequest(
        List.of(new CreateReturnLine(allocationId, 2)))))
        .isInstanceOf(ReturnExceededException.class);
    var inv = invRepo.findByProduct_Id(p.getId()).get(0);
    assertThat(inv.getAvailable()).isEqualTo(4);
  }

  @Test
  void duplicateAllocationInOneRequestIsRejected() {
    assertThatThrownBy(() -> returns.create(new CreateReturnRequest(List.of(
        new CreateReturnLine(allocationId, 1),
        new CreateReturnLine(allocationId, 1)))))
        .isInstanceOf(DomainException.class);
    assertThat(returnRepo.count()).isZero();
  }
}
