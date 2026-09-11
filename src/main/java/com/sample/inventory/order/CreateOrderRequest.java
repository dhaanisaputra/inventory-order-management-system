package com.sample.inventory.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateOrderRequest(
    @Size(min = 1, max = 100) List<@Valid CreateOrderLine> lines) {

  public record CreateOrderLine(@NotNull Long productId, @Positive int qty) {}
}
