package com.sample.inventory.insights.recommendation;

public record RecommendationDto(Long productId, String productSku, long boughtTogetherCount) {}
