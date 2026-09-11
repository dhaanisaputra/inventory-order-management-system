package com.sample.inventory.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

  @Mock OutboxRepository repo;
  @Mock KafkaTemplate<String, String> kafka;

  private final Clock clock = Clock.fixed(Instant.parse("2026-09-11T00:00:00Z"), ZoneOffset.UTC);

  @Test
  @SuppressWarnings("unchecked")
  void publishesUnpublishedAndMarksThem() {
    var relay = new OutboxRelayService(repo, kafka, clock);
    var e = new OutboxEvent("inventory.order.created", "7", "{\"orderId\":7}");
    when(repo.lockUnpublished(50)).thenReturn(List.of(e));
    when(kafka.send(eq("inventory.order.created"), eq("7"), anyString()))
        .thenReturn(CompletableFuture.completedFuture(null));
    int n = relay.relayBatch();
    assertThat(n).isEqualTo(1);
    assertThat(e.getPublishedAt()).isEqualTo(clock.instant());
    verify(kafka).send("inventory.order.created", "7", "{\"orderId\":7}");
  }

  @Test
  @SuppressWarnings("unchecked")
  void brokerFailureLeavesRowUnpublished() {
    var relay = new OutboxRelayService(repo, kafka, clock);
    var e = new OutboxEvent("t", "k", "{}");
    when(repo.lockUnpublished(50)).thenReturn(List.of(e));
    when(kafka.send(eq("t"), eq("k"), eq("{}")))
        .thenReturn(CompletableFuture.failedFuture(new RuntimeException("down")));
    int n = relay.relayBatch();
    assertThat(n).isZero();
    assertThat(e.getPublishedAt()).isNull();
  }
}
