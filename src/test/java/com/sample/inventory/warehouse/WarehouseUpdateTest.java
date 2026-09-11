package com.sample.inventory.warehouse;

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
class WarehouseUpdateTest {

  @Mock WarehouseRepository repo;
  @InjectMocks WarehouseService service;

  @Test
  void reprioritizeChangesPriority() {
    var w = new Warehouse("JKT-1", "Jakarta", 10);
    when(repo.findById(1L)).thenReturn(Optional.of(w));
    var out = service.update(1L, new UpdateWarehouseRequest(null, 5));
    assertThat(out.priority()).isEqualTo(5);
  }

  @Test
  void blankNameIsIgnored() {
    var w = new Warehouse("JKT-1", "Jakarta", 10);
    when(repo.findById(1L)).thenReturn(Optional.of(w));
    var out = service.update(1L, new UpdateWarehouseRequest("  ", null));
    assertThat(out.name()).isEqualTo("Jakarta");
  }

  @Test
  void concurrentDuplicateMapsToDuplicateException() {
    when(repo.existsByCode("JKT-1")).thenReturn(false);
    when(repo.save(any())).thenThrow(new DataIntegrityViolationException("dup"));
    assertThatThrownBy(() -> service.create(new WarehouseRequest("JKT-1", "Jakarta", 10)))
        .isInstanceOf(DuplicateException.class);
  }
}
