package com.sample.inventory.product;

import jakarta.validation.constraints.Size;

public record UpdateProductRequest(@Size(max = 255) String name, Boolean active) {}
