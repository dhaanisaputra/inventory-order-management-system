package com.sample.inventory.movement;

import java.time.Clock;
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
  private final Clock clock;

  public Page<MovementResponse> search(
      Long productId,
      Long warehouseId,
      MovementType type,
      Instant from,
      Instant to,
      Pageable pageable) {
    Instant f = from != null ? from : Instant.EPOCH;
    Instant t = to != null ? to : clock.instant();
    return repo.search(productId, warehouseId, type, f, t, pageable)
        .map(MovementMapper::toResponse);
  }
}
