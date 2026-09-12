package com.sample.inventory.insights.forecast;

import com.sample.inventory.common.error.NotFoundException;
import com.sample.inventory.order.OrderLineRepository;
import com.sample.inventory.order.OrderStatus;
import com.sample.inventory.product.ProductRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DemandForecastService {

  private static final int WINDOW_DAYS = 30;

  private final OrderLineRepository lineRepo;
  private final ProductRepository productRepo;
  private final Clock clock;

  public DemandForecastResponse forecast(long productId, int horizonDays) {
    var product = productRepo.findById(productId)
        .orElseThrow(() -> new NotFoundException("product", productId));
    int horizon = Math.min(Math.max(horizonDays, 1), 90);
    var since = clock.instant().minusSeconds((long) WINDOW_DAYS * 24 * 3600);
    var lines = lineRepo.findConfirmedByProductSince(productId, OrderStatus.CONFIRMED, since);
    var perDay = new HashMap<LocalDate, Integer>();
    for (var l : lines) {
      var day = l.getOrder().getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
      perDay.merge(day, l.getQty(), Integer::sum);
    }
    int sum = perDay.values().stream().mapToInt(Integer::intValue).sum();
    double avg = (double) sum / WINDOW_DAYS;
    return new DemandForecastResponse(productId, product.getSku(), WINDOW_DAYS,
        avg, horizon, avg * horizon, "moving-average-30d");
  }
}
