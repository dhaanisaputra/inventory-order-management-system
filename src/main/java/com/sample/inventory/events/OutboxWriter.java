package com.sample.inventory.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OutboxWriter {

  private final OutboxRepository repo;
  private final ObjectMapper objectMapper;

  @Transactional(propagation = Propagation.MANDATORY)
  public void write(String topic, String key, Object event) {
    try {
      repo.save(new OutboxEvent(topic, key, objectMapper.writeValueAsString(event)));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("outbox serialize failed", e);
    }
  }
}
