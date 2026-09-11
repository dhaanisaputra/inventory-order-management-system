package com.sample.inventory.events;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class OutboxWriter {

  private final OutboxRepository repo;
  private final ObjectMapper objectMapper;

  @Transactional(propagation = Propagation.MANDATORY)
  public void write(String topic, String key, Object event) {
    try {
      repo.save(new OutboxEvent(topic, key, objectMapper.writeValueAsString(event)));
    } catch (JacksonException e) {
      throw new IllegalStateException("outbox serialize failed", e);
    }
  }
}
