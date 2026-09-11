package com.sample.inventory.product;

public record ProductResponse(Long id, String sku, String name, boolean active) {}
