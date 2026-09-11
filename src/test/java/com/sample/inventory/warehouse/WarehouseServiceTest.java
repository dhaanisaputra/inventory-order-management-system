package com.sample.inventory.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.sample.inventory.common.error.DuplicateException;
import com.sample.inventory.common.error.NotFoundException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class WarehouseServiceTest {

  @Mock WarehouseRepository repo;
  @InjectMocks WarehouseService service;

  @Test
  void createsWarehouse() {
    when(repo.existsByCode("JKT-1")).thenReturn(false);
    when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
    var out = service.create(new WarehouseRequest("JKT-1", "Jakarta", 10));
    assertThat(out.code()).isEqualTo("JKT-1");
  }

  @Test
  void rejectsDuplicateCode() {
    when(repo.existsByCode("JKT-1")).thenReturn(true);
    assertThatThrownBy(() -> service.create(new WarehouseRequest("JKT-1", "Jakarta", 10)))
        .isInstanceOf(DuplicateException.class);
  }

  @Test
  void listReturnsPriorityOrdered() {
    var w = new Warehouse("A", "A", 5);
    when(repo.findAll(Sort.by("priority").ascending())).thenReturn(List.of(w));
    assertThat(service.list()).extracting(WarehouseResponse::code).containsExactly("A");
  }

  @Test
  void getMissingThrows() {
    when(repo.findById(9L)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.get(9L)).isInstanceOf(NotFoundException.class);
  }
}
