package com.sample.inventory.transfer;

import java.time.Instant;

public record TransferResponse(
    Long id,
    Long productId,
    String productSku,
    Long fromWarehouseId,
    String fromWarehouseCode,
    Long toWarehouseId,
    String toWarehouseCode,
    int qty,
    Instant createdAt) {}
