package com.sample.inventory.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.sample.inventory.TestcontainersConfiguration;
import com.sample.inventory.common.error.NotFoundException;
import com.sample.inventory.insights.forecast.DemandForecastService;
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
class ForecastIT {

  @Autowired DemandForecastService forecast;
  @Autowired OrderService orders;
  @Autowired SalesOrderRepository orderRepo;
  @Autowired OrderIdempotencyRepository idemRepo;
  @Autowired ReservationRepository reservationRepo;
  @Autowired InventoryRepository invRepo;
  @Autowired ProductRepository productRepo;
  @Autowired WarehouseRepository warehouseRepo;
  @Autowired StockMovementRepository movementRepo;

  Product p;

  @BeforeEach
  void seed() {
    movementRepo.deleteAll();
    reservationRepo.deleteAll();
    idemRepo.deleteAll();
    orderRepo.deleteAll();
    invRepo.deleteAll();
    p = productRepo.save(new Product("SKU-" + System.nanoTime(), "Keyboard"));
    var w = warehouseRepo.save(new Warehouse("W-" + System.nanoTime(), "W", 1));
    invRepo.save(new Inventory(p, w, 100, 0, 1));
    var order =
        orders.create(
            new CreateOrderRequest(List.of(new CreateOrderLine(p.getId(), 10))), null, null);
    orders.confirm(order.id());
  }

  @Test
  void forecastsFromConfirmedHistory() {
    var res = forecast.forecast(p.getId(), 30);
    assertThat(res.productSku()).isEqualTo(p.getSku());
    assertThat(res.avgDailyQty()).isCloseTo(10.0 / 30.0, within(0.000001));
    assertThat(res.forecastedQty()).isCloseTo(10.0, within(0.0001));
    assertThat(res.method()).isEqualTo("moving-average-30d");
  }

  @Test
  void unknownProductThrowsNotFound() {
    assertThatThrownBy(() -> forecast.forecast(999999L, 30)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void badHorizonRejected() {
    assertThat(forecast.forecast(p.getId(), 1).forecastedQty())
        .isCloseTo(10.0 / 30.0, within(0.000001));
  }
}
