package com.sample.inventory.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class InventoryCommonTest {

  @Test
  void pagedResultMapsFromSpringPage() {
    var page = new PageImpl<>(List.of("a", "b"), PageRequest.of(1, 20), 42);
    var result = PagedResult.from(page);
    assertThat(result.content()).containsExactly("a", "b");
    assertThat(result.page()).isEqualTo(1);
    assertThat(result.size()).isEqualTo(20);
    assertThat(result.totalElements()).isEqualTo(42);
    assertThat(result.totalPages()).isEqualTo(3);
  }

  @Test
  void sortValidatorFallsBackOnUnknownProperty() {
    Pageable in = PageRequest.of(0, 20, Sort.by("hacker"));
    Pageable out = SortValidator.validated(in, Set.of("name"), Sort.by("name").ascending());
    assertThat(out.getSort().getOrderFor("name")).isNotNull();
    assertThat(out.getSort().getOrderFor("hacker")).isNull();
  }

  @Test
  void sortValidatorKeepsAllowedSort() {
    Pageable in = PageRequest.of(0, 20, Sort.by("name").descending());
    Pageable out = SortValidator.validated(in, Set.of("name"), Sort.by("name").ascending());
    assertThat(out.getSort().getOrderFor("name").getDirection()).isEqualTo(Sort.Direction.DESC);
  }
}
