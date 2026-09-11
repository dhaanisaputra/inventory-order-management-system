package com.sample.inventory.events;

import static org.assertj.core.api.Assertions.assertThatNoException;

import org.junit.jupiter.api.Test;

class LowStockAlertListenerTest {

  @Test
  void listenerAcceptsPayloadWithoutThrowing() {
    assertThatNoException()
        .isThrownBy(() -> new LowStockAlertListener().onMessage("{\"productId\":11}"));
  }
}
