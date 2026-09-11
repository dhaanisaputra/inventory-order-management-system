package com.sample.inventory.events;

public record LowStockEvent(long productId, long warehouseId, int available, int threshold) {}
