package com.sample.inventory.order;

import java.time.Instant;
import java.util.List;

public record OrderResponse(
    Long id, OrderStatus status, List<OrderLineDto> lines, Instant createdAt) {

  public record OrderLineDto(
      Long id, Long productId, String productSku, int qty, List<AllocationDto> allocations) {}

  public record AllocationDto(Long id, Long warehouseId, String warehouseCode, int qty) {}
}
