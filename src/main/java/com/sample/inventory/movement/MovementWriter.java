package com.sample.inventory.movement;

import com.sample.inventory.product.Product;
import com.sample.inventory.warehouse.Warehouse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MovementWriter {

  private final StockMovementRepository repo;

  @Transactional(propagation = Propagation.MANDATORY)
  public void write(Product product, Warehouse warehouse, MovementType type,
      int qty, String refType, long refId) {
    repo.save(StockMovement.of(product, warehouse, type, qty, refType, refId));
  }
}
