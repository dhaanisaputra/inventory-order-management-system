package com.sample.inventory.order;

import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReservationExpiryWorker {

  private final ReservationExpiryService expiry;
  private final Clock clock;

  @Scheduled(fixedDelayString = "${app.reservation.expire-interval:PT60S}")
  public void run() {
    expiry.expireBatch(clock.instant());
  }
}
