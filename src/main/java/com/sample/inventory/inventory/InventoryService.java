package com.sample.inventory.inventory;

import com.sample.inventory.common.error.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryService {

  private final InventoryRepository repo;

  public InventoryResponse get(long id) {
    return repo.findById(id)
        .map(InventoryMapper::toResponse)
        .orElseThrow(() -> new NotFoundException("inventory", id));
  }

  public Page<InventoryResponse> search(
      Long productId, Long warehouseId, boolean lowOnly, Pageable pageable) {
    return repo.search(productId, warehouseId, lowOnly, pageable).map(InventoryMapper::toResponse);
  }
}
