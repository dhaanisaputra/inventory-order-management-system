package com.sample.inventory.warehouse;

import static org.assertj.core.api.Assertions.assertThat;

import com.sample.inventory.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Sort;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
class WarehouseRepositoryIT {

  @Autowired WarehouseRepository repo;

  @Test
  void persistsAndEnforcesCodeUniqueness() {
    repo.save(new Warehouse("JKT-1", "Jakarta", 10));
    assertThat(repo.findByCode("JKT-1")).isPresent();
    assertThat(repo.existsByCode("JKT-1")).isTrue();
  }

  @Test
  void findAllOrderedByPriority() {
    repo.save(new Warehouse("B", "B", 20));
    repo.save(new Warehouse("A", "A", 5));
    List<Warehouse> all = repo.findAll(Sort.by("priority").ascending());
    assertThat(all).extracting(Warehouse::getCode).containsExactly("A", "B");
  }
}
