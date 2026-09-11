package com.sample.inventory.movement;

import java.time.Instant;

public record MovementResponse(Long id, Long productId, String productSku, Long warehouseId,
    String warehouseCode, MovementType type, int qty, String refType, long refId, Instant createdAt) {}
