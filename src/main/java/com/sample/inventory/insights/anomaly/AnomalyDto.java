package com.sample.inventory.insights.anomaly;

import java.time.Instant;

public record AnomalyDto(
    AnomalyType type, Long productId, String severity, String detail, Instant detectedAt) {}
