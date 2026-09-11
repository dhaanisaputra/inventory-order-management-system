package com.sample.inventory.purchase;

import java.time.Instant;
import java.util.List;

public record PurchaseResponse(Long id, PurchaseOrderStatus status,
    List<PurchaseLineDto> lines, Instant createdAt) {

  public record PurchaseLineDto(Long id, Long productId, String productSku,
      int orderedQty, int receivedQty) {}
}
