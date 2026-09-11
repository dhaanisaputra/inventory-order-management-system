package com.sample.inventory.purchase;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ReceiveRequest(
    @Size(min = 1, max = 100) List<@Valid ReceiveItem> items) {

  public record ReceiveItem(@NotNull Long productId, @NotNull Long warehouseId, @Positive int qty) {}
}
