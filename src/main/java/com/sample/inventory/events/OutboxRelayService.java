package com.sample.inventory.events;

import java.time.Clock;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OutboxRelayService {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelayService.class);
  private static final int BATCH_SIZE = 50;

  private final OutboxRepository repo;
  private final KafkaTemplate<String, String> kafka;
  private final Clock clock;

  @Transactional
  public int relayBatch() {
    var batch = repo.lockUnpublished(BATCH_SIZE);
    int sent = 0;
    for (var e : batch) {
      try {
        kafka.send(e.getTopic(), e.getEventKey(), e.getPayload()).get(5, TimeUnit.SECONDS);
      } catch (Exception ex) {
        if (ex instanceof InterruptedException ie) {
          Thread.currentThread().interrupt();
        }
        log.warn("Outbox publish failed, will retry later", ex);
        break;
      }
      e.markPublished(clock.instant());
      sent++;
    }
    return sent;
  }
}
