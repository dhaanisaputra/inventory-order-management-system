package com.sample.inventory.events;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sample.inventory.inventory.Inventory;
import com.sample.inventory.product.Product;
import com.sample.inventory.warehouse.Warehouse;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class LowStockNotifierTest {

  @Mock KafkaTemplate<String, String> kafka;

  private LowStockNotifier notifier() {
    return new LowStockNotifier(kafka, new ObjectMapper());
  }

  private Inventory inv(int available, int threshold) {
    var p = new Product("SKU-1", "Keyboard");
    ReflectionTestUtils.setField(p, "id", 11L);
    var w = new Warehouse("JKT-1", "Jakarta", 10);
    ReflectionTestUtils.setField(w, "id", 22L);
    return new Inventory(p, w, available, 0, threshold);
  }

  @Test
  @SuppressWarnings("unchecked")
  void publishesWhenLow() {
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(null));
    notifier().notifyIfLow(inv(2, 5));
    verify(kafka).send(eq("inventory.stock.low"), eq("11"), anyString());
  }

  @Test
  void silentWhenHealthy() {
    notifier().notifyIfLow(inv(10, 5));
    verify(kafka, never()).send(anyString(), anyString(), anyString());
  }

  @Test
  void brokerFailureIsSwallowed() {
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.failedFuture(new RuntimeException("down")));
    assertThatNoException().isThrownBy(() -> notifier().notifyIfLow(inv(1, 5)));
  }
}
