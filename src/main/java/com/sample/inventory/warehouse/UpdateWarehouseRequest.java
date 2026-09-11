package com.sample.inventory.warehouse;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record UpdateWarehouseRequest(@Size(max = 255) String name, @Min(0) Integer priority) {}
