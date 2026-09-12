package com.sample.inventory.insights.anomaly;

import com.sample.inventory.movement.MovementType;
import com.sample.inventory.movement.StockMovement;
import com.sample.inventory.movement.StockMovementRepository;
import com.sample.inventory.order.ReservationRepository;
import com.sample.inventory.order.ReservationStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnomalyService {

  private final StockMovementRepository movementRepo;
  private final ReservationRepository reservationRepo;
  private final Clock clock;

  public List<AnomalyDto> scan(int days) {
    int window = Math.min(Math.max(days, 1), 90);
    var since = clock.instant().minusSeconds((long) window * 24 * 3600);
    var out = new ArrayList<AnomalyDto>();
    out.addAll(detectSpikes(since));
    out.addAll(detectReturnAbuse(since));
    out.addAll(detectExpiryRate());
    return out;
  }

  private List<AnomalyDto> detectSpikes(Instant since) {
    var moves = movementRepo.findByTypeAndCreatedAtAfter(MovementType.OUT, since);
    var byProduct = new HashMap<Long, List<StockMovement>>();
    for (var m : moves) {
      byProduct.computeIfAbsent(m.getProduct().getId(), k -> new ArrayList<>()).add(m);
    }
    var out = new ArrayList<AnomalyDto>();
    for (var e : byProduct.entrySet()) {
      var list = e.getValue();
      if (list.size() < 4) {
        continue;
      }
      var latest = list.stream().max(Comparator.comparing(StockMovement::getId)).orElseThrow();
      var prior = list.stream().filter(m -> !m.getId().equals(latest.getId())).toList();
      if (prior.size() < 3) {
        continue;
      }
      double avg = prior.stream().mapToInt(StockMovement::getQty).average().orElse(0);
      if (avg > 0 && latest.getQty() > 3 * avg) {
        var severity = latest.getQty() > 5 * avg ? "HIGH" : "MEDIUM";
        out.add(new AnomalyDto(AnomalyType.SPIKE, e.getKey(), severity,
            "OUT " + latest.getQty() + " exceeds 3x avg " + avg, clock.instant()));
      }
    }
    return out;
  }

  private List<AnomalyDto> detectReturnAbuse(Instant since) {
    var returns = new HashMap<Long, Integer>();
    for (var m : movementRepo.findByTypeAndCreatedAtAfter(MovementType.IN, since)) {
      if ("RETURN".equals(m.getRefType())) {
        returns.merge(m.getProduct().getId(), m.getQty(), Integer::sum);
      }
    }
    var outs = new HashMap<Long, Integer>();
    for (var m : movementRepo.findByTypeAndCreatedAtAfter(MovementType.OUT, since)) {
      outs.merge(m.getProduct().getId(), m.getQty(), Integer::sum);
    }
    var out = new ArrayList<AnomalyDto>();
    for (var e : returns.entrySet()) {
      int ret = e.getValue();
      int o = outs.getOrDefault(e.getKey(), 0);
      if (ret >= 3 && o > 0 && (double) ret / (ret + o) > 0.5) {
        out.add(new AnomalyDto(AnomalyType.HIGH_RETURNS, e.getKey(), "HIGH",
            "returned " + ret + " of " + (ret + o) + " moved", clock.instant()));
      }
    }
    return out;
  }

  private List<AnomalyDto> detectExpiryRate() {
    long expired = reservationRepo.countByStatus(ReservationStatus.EXPIRED);
    long confirmed = reservationRepo.countByStatus(ReservationStatus.CONFIRMED);
    long total = expired + confirmed;
    var out = new ArrayList<AnomalyDto>();
    if (total >= 5 && (double) expired / total > 0.5) {
      out.add(new AnomalyDto(AnomalyType.HIGH_EXPIRY, null, "MEDIUM",
          "expired " + expired + " of " + total + " terminal reservations", clock.instant()));
    }
    return out;
  }
}
