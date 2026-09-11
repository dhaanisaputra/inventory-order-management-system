package com.sample.inventory.returns;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateReturnRequest(
    @Size(min = 1, max = 100) List<@Valid CreateReturnLine> lines) {

  public record CreateReturnLine(@NotNull Long allocationId, @Positive int qty) {}
}
