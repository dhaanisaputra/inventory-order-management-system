package com.sample.inventory.movement;

import com.sample.inventory.events.KafkaTopics;
import com.sample.inventory.events.OutboxWriter;
import com.sample.inventory.product.Product;
import com.sample.inventory.warehouse.Warehouse;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MovementWriter {

  private final StockMovementRepository repo;
  private final OutboxWriter outbox;

  @Transactional(propagation = Propagation.MANDATORY)
  public void write(
      Product product,
      Warehouse warehouse,
      MovementType type,
      int qty,
      String refType,
      long refId) {
    repo.save(StockMovement.of(product, warehouse, type, qty, refType, refId));
    outbox.write(KafkaTopics.STOCK_MOVEMENT, String.valueOf(product.getId()),
        Map.of("productId", product.getId(), "warehouseId", warehouse.getId(),
            "type", type.name(), "qty", qty, "refType", refType, "refId", refId));
  }
}
