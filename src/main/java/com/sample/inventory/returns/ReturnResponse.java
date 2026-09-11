package com.sample.inventory.returns;

import java.time.Instant;
import java.util.List;

public record ReturnResponse(Long id, List<ReturnLineDto> lines, Instant createdAt) {

  public record ReturnLineDto(Long id, Long allocationId, Long productId, String productSku,
      Long warehouseId, String warehouseCode, int qty) {}
}
