package com.sample.inventory.events;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutboxRelayWorker {

  private final OutboxRelayService relay;

  @Scheduled(fixedDelayString = "${app.outbox.relay-interval:PT10S}")
  public void run() {
    relay.relayBatch();
  }
}
