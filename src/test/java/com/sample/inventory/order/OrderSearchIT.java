package com.sample.inventory.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.sample.inventory.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OrderSearchIT {

  @Autowired OrderService service;
  @Autowired SalesOrderRepository orders;

  @Test
  void searchWithAllNullsDoesNot500() {
    orders.save(new SalesOrder());
    var page = service.search(null, null, null, Pageable.unpaged());
    assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(1);
  }
}
