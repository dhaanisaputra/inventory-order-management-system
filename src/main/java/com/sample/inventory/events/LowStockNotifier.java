package com.sample.inventory.events;

import com.sample.inventory.inventory.Inventory;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class LowStockNotifier {

  private static final Logger log = LoggerFactory.getLogger(LowStockNotifier.class);

  private final KafkaTemplate<String, String> kafka;
  private final ObjectMapper objectMapper;

  public void notifyIfLow(Inventory inv) {
    if (!inv.isLowStock()) {
      return;
    }
    try {
      var event =
          new LowStockEvent(
              inv.getProduct().getId(),
              inv.getWarehouse().getId(),
              inv.getAvailable(),
              inv.getLowStockThreshold());
      kafka.send(
          KafkaTopics.STOCK_LOW,
          String.valueOf(inv.getProduct().getId()),
          objectMapper.writeValueAsString(event));
    } catch (Exception e) {
      log.warn("Failed to publish low-stock event", e);
    }
  }
}
