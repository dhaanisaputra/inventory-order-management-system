package com.sample.inventory.movement;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MovementService {

  private final StockMovementRepository repo;

  public Page<MovementResponse> search(Long productId, Long warehouseId, MovementType type,
      Instant from, Instant to, Pageable pageable) {
    return repo.search(productId, warehouseId, type, from, to, pageable).map(MovementMapper::toResponse);
  }
}
