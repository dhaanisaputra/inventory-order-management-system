package com.sample.inventory.transfer;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record TransferRequest(@NotNull Long productId, @NotNull Long fromWarehouseId,
    @NotNull Long toWarehouseId, @Positive int qty) {}
