package com.sample.inventory.insights;

import static org.assertj.core.api.Assertions.assertThat;

import com.sample.inventory.TestcontainersConfiguration;
import com.sample.inventory.insights.recommendation.RecommendationService;
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
class RecommendationIT {

  @Autowired RecommendationService recommendations;
  @Autowired OrderService orders;
  @Autowired SalesOrderRepository orderRepo;
  @Autowired ReservationRepository reservationRepo;
  @Autowired OrderIdempotencyRepository idemRepo;
  @Autowired InventoryRepository invRepo;
  @Autowired ProductRepository productRepo;
  @Autowired WarehouseRepository warehouseRepo;
  @Autowired StockMovementRepository movementRepo;

  Product a;
  Product b;
  Product c;

  @BeforeEach
  void seed() {
    movementRepo.deleteAll();
    reservationRepo.deleteAll();
    idemRepo.deleteAll();
    orderRepo.deleteAll();
    invRepo.deleteAll();
    a = productRepo.save(new Product("A-" + System.nanoTime(), "Alpha"));
    b = productRepo.save(new Product("B-" + System.nanoTime(), "Beta"));
    c = productRepo.save(new Product("C-" + System.nanoTime(), "Gamma"));
    var w = warehouseRepo.save(new Warehouse("W-" + System.nanoTime(), "W", 1));
    invRepo.save(new Inventory(a, w, 100, 0, 1));
    invRepo.save(new Inventory(b, w, 100, 0, 1));
    invRepo.save(new Inventory(c, w, 100, 0, 1));
    for (int i = 0; i < 2; i++) {
      var o = orders.create(new CreateOrderRequest(List.of(
          new CreateOrderLine(a.getId(), 1), new CreateOrderLine(b.getId(), 1))), null, null);
      orders.confirm(o.id());
    }
    var o3 = orders.create(new CreateOrderRequest(List.of(
        new CreateOrderLine(a.getId(), 1), new CreateOrderLine(c.getId(), 1))), null, null);
    orders.confirm(o3.id());
  }

  @Test
  void recommendsFrequentBasketMate() {
    var recs = recommendations.forProduct(a.getId());
    assertThat(recs).extracting(r -> r.productSku())
        .contains(b.getSku())
        .doesNotContain(c.getSku());
    assertThat(recs.get(0).boughtTogetherCount()).isEqualTo(2);
  }

  @Test
  void unknownProductYieldsEmpty() {
    var q = productRepo.save(new Product("Q-" + System.nanoTime(), "Q"));
    assertThat(recommendations.forProduct(q.getId())).isEmpty();
  }
}
