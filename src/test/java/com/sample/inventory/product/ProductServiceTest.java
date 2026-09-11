package com.sample.inventory.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.sample.inventory.common.error.DuplicateException;
import com.sample.inventory.common.error.NotFoundException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

  @Mock ProductRepository repo;
  @InjectMocks ProductService service;

  @Test
  void createsProduct() {
    when(repo.existsBySku("SKU-1")).thenReturn(false);
    when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
    var out = service.create(new ProductRequest("SKU-1", "Keyboard"));
    assertThat(out.sku()).isEqualTo("SKU-1");
  }

  @Test
  void rejectsDuplicateSku() {
    when(repo.existsBySku("SKU-1")).thenReturn(true);
    assertThatThrownBy(() -> service.create(new ProductRequest("SKU-1", "Keyboard")))
        .isInstanceOf(DuplicateException.class);
  }

  @Test
  void getMissingThrowsNotFound() {
    when(repo.findById(9L)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.get(9L)).isInstanceOf(NotFoundException.class);
  }
}
