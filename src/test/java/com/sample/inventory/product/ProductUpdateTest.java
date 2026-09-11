package com.sample.inventory.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.sample.inventory.common.error.DuplicateException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class ProductUpdateTest {

  @Mock ProductRepository repo;
  @InjectMocks ProductService service;

  @Test
  void renameChangesName() {
    var p = new Product("SKU-1", "Old");
    when(repo.findById(1L)).thenReturn(Optional.of(p));
    var out = service.update(1L, new UpdateProductRequest("New", null));
    assertThat(out.name()).isEqualTo("New");
    assertThat(out.active()).isTrue();
  }

  @Test
  void blankNameIsIgnored() {
    var p = new Product("SKU-1", "Old");
    when(repo.findById(1L)).thenReturn(Optional.of(p));
    var out = service.update(1L, new UpdateProductRequest("  ", null));
    assertThat(out.name()).isEqualTo("Old");
  }

  @Test
  void deactivateSetsInactive() {
    var p = new Product("SKU-1", "Old");
    when(repo.findById(1L)).thenReturn(Optional.of(p));
    var out = service.update(1L, new UpdateProductRequest(null, false));
    assertThat(out.active()).isFalse();
  }

  @Test
  void concurrentDuplicateMapsToDuplicateException() {
    when(repo.existsBySku("SKU-1")).thenReturn(false);
    when(repo.save(any())).thenThrow(new DataIntegrityViolationException("dup"));
    assertThatThrownBy(() -> service.create(new ProductRequest("SKU-1", "x")))
        .isInstanceOf(DuplicateException.class);
  }
}
