package com.sample.inventory.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class OutboxWriterTest {

  @Mock OutboxRepository repo;

  @Test
  void writesRowWithTopicKeyAndJsonPayload() {
    var writer = new OutboxWriter(repo, new ObjectMapper());
    writer.write(KafkaTopics.ORDER_CREATED, "42", Map.of("orderId", 42));
    var cap = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(repo).save(cap.capture());
    assertThat(cap.getValue().getTopic()).isEqualTo("inventory.order.created");
    assertThat(cap.getValue().getEventKey()).isEqualTo("42");
    assertThat(cap.getValue().getPayload()).contains("42");
    assertThat(cap.getValue().getPublishedAt()).isNull();
  }
}
