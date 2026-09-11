package com.sample.inventory.inventory;

public record InventoryResponse(Long id, Long productId, String productSku, Long warehouseId,
    String warehouseCode, int available, int reserved, int lowStockThreshold, boolean lowStock) {}
