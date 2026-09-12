package com.sample.inventory.insights.replenishment;

import com.sample.inventory.insights.forecast.DemandForecastService;
import com.sample.inventory.inventory.InventoryRepository;
import com.sample.inventory.product.ProductRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReplenishmentService {

  private final DemandForecastService forecast;
  private final InventoryRepository invRepo;
  private final ProductRepository productRepo;
  private final ReplenishmentProperties props;

  public List<ReplenishmentSuggestion> suggestAll() {
    var out = new ArrayList<ReplenishmentSuggestion>();
    for (var product : productRepo.findAll()) {
      if (!product.isActive()) {
        continue;
      }
      var fc = forecast.forecast(product.getId(), 30);
      double reorderPoint =
          fc.avgDailyQty() * props.leadTimeDays() + fc.avgDailyQty() * props.safetyDays();
      int available =
          invRepo.findByProductId(product.getId()).stream().mapToInt(i -> i.getAvailable()).sum();
      if (available < reorderPoint) {
        int suggested = (int) Math.ceil(reorderPoint - available);
        out.add(
            new ReplenishmentSuggestion(
                product.getId(),
                product.getSku(),
                available,
                reorderPoint,
                suggested,
                "available "
                    + available
                    + " below reorder point "
                    + reorderPoint
                    + " (lead "
                    + props.leadTimeDays()
                    + "d + safety "
                    + props.safetyDays()
                    + "d)"));
      }
    }
    out.sort(
        (a, b) ->
            Double.compare(b.reorderPoint() - b.available(), a.reorderPoint() - a.available()));
    return out;
  }
}
