package com.sample.inventory.insights.recommendation;

import com.sample.inventory.order.OrderLineRepository;
import com.sample.inventory.order.OrderStatus;
import com.sample.inventory.product.ProductRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecommendationService {

  private static final long MIN_SUPPORT = 2;
  private static final int LIMIT = 5;

  private final OrderLineRepository lineRepo;
  private final ProductRepository productRepo;

  public List<RecommendationDto> forProduct(long productId) {
    var rows = lineRepo.countBasketMates(productId, OrderStatus.CONFIRMED, MIN_SUPPORT);
    var out = new ArrayList<RecommendationDto>();
    for (var row : rows.stream().limit(LIMIT).toList()) {
      Long id = (Long) row[0];
      long cnt = (Long) row[1];
      var sku = productRepo.findById(id).map(p -> p.getSku()).orElse("?");
      out.add(new RecommendationDto(id, sku, cnt));
    }
    return out;
  }
}
