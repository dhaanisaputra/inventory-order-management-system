package com.sample.inventory.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ErrorCodeTest {

  @Test
  void formatsNewCodes() {
    assertThat(ErrorCode.INSUFFICIENT_STOCK.httpStatus()).isEqualTo(409);
    assertThat(ErrorCode.INSUFFICIENT_STOCK.format("KB-100"))
        .isEqualTo("stock insufficient for product KB-100");
    assertThat(ErrorCode.STOCK_CONTENTION.httpStatus()).isEqualTo(409);
    assertThat(ErrorCode.INVALID_TRANSITION.format(7L, "CONFIRMED"))
        .isEqualTo("order 7 cannot transition from CONFIRMED");
    assertThat(ErrorCode.IDEMPOTENCY_KEY_CONFLICT.httpStatus()).isEqualTo(422);
  }
}
