package com.sample.inventory.insights.forecast;

public record DemandForecastResponse(
    Long productId,
    String productSku,
    int windowDays,
    double avgDailyQty,
    int horizonDays,
    double forecastedQty,
    String method) {}
