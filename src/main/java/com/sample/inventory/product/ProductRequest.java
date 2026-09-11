package com.sample.inventory.product;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProductRequest(
    @NotBlank @Size(max = 64) String sku, @NotBlank @Size(max = 255) String name) {}
