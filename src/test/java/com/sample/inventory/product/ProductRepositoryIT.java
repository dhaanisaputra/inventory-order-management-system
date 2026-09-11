package com.sample.inventory.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.sample.inventory.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
class ProductRepositoryIT {

  @Autowired ProductRepository repo;

  @Test
  void persistsAndFindsBySku() {
    repo.save(new Product("SKU-1", "Keyboard"));
    assertThat(repo.findBySku("SKU-1")).isPresent();
    assertThat(repo.existsBySku("SKU-1")).isTrue();
  }

  @Test
  void searchMatchesSkuOrName() {
    repo.save(new Product("KB-100", "Keyboard"));
    repo.save(new Product("MS-200", "Mouse"));
    var page = repo.search("key", org.springframework.data.domain.Pageable.unpaged());
    assertThat(page.getContent()).extracting(Product::getSku).containsExactly("KB-100");
  }
}
