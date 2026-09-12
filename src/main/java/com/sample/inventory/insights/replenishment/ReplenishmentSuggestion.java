package com.sample.inventory.insights.replenishment;

public record ReplenishmentSuggestion(Long productId, String productSku, int available,
    double reorderPoint, int suggestedQty, String reason) {}
