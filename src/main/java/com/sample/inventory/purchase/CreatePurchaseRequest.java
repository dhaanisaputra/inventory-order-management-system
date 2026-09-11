package com.sample.inventory.purchase;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreatePurchaseRequest(
    @Size(min = 1, max = 100) List<@Valid CreatePurchaseLine> lines) {

  public record CreatePurchaseLine(@NotNull Long productId, @Positive int orderedQty) {}
}
