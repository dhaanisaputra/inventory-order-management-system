package com.sample.inventory.warehouse;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WarehouseRequest(
    @NotBlank @Size(max = 64) String code,
    @NotBlank @Size(max = 255) String name,
    @Min(0) int priority) {}
