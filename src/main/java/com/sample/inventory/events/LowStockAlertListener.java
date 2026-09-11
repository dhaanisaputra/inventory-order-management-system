package com.sample.inventory.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class LowStockAlertListener {

  private static final Logger log = LoggerFactory.getLogger(LowStockAlertListener.class);

  @KafkaListener(topics = KafkaTopics.STOCK_LOW, groupId = "inventory-alerts")
  public void onMessage(String payload) {
    log.warn("LOW STOCK alert: {}", payload);
  }
}
