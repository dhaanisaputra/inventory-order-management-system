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

  // Rule thresholds (rule-based v1 heuristics)
  private static final int SPIKE_MIN_SAMPLES = 4;
  private static final int SPIKE_PRIOR_MIN = 3;
  private static final double SPIKE_FACTOR = 3.0;
  private static final double SPIKE_HIGH_FACTOR = 5.0;
  private static final int RETURNS_MIN_QTY = 3;
  private static final double RETURNS_RATIO = 0.5;
  private static final int EXPIRY_MIN_TOTAL = 5;
  private static final double EXPIRY_RATIO = 0.5;

  public List<AnomalyDto> scan(int days) {
    int window = Math.min(Math.max(days, 1), 90);
    var since = clock.instant().minusSeconds((long) window * 24 * 3600);
    var out = new ArrayList<AnomalyDto>();
    out.addAll(detectSpikes(since));
    out.addAll(detectReturnAbuse(since));
    out.addAll(detectExpiryRate(since));
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
      if (list.size() < SPIKE_MIN_SAMPLES) {
        continue;
      }
      var latest = list.stream().max(Comparator.comparing(StockMovement::getId)).orElseThrow();
      var prior = list.stream().filter(m -> !m.getId().equals(latest.getId())).toList();
      if (prior.size() < SPIKE_PRIOR_MIN) {
        continue;
      }
      double avg = prior.stream().mapToInt(StockMovement::getQty).average().orElse(0);
      if (avg > 0 && latest.getQty() > SPIKE_FACTOR * avg) {
        var severity = latest.getQty() > SPIKE_HIGH_FACTOR * avg ? "HIGH" : "MEDIUM";
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
      if (ret >= RETURNS_MIN_QTY && o > 0 && (double) ret / (ret + o) > RETURNS_RATIO) {
        out.add(new AnomalyDto(AnomalyType.HIGH_RETURNS, e.getKey(), "HIGH",
            "returned " + ret + " of " + (ret + o) + " moved", clock.instant()));
      }
    }
    return out;
  }

  private List<AnomalyDto> detectExpiryRate(Instant since) {
    long expired = reservationRepo.countByStatusAndCreatedAtAfter(ReservationStatus.EXPIRED, since);
    long confirmed = reservationRepo.countByStatusAndCreatedAtAfter(ReservationStatus.CONFIRMED, since);
    long total = expired + confirmed;
    var out = new ArrayList<AnomalyDto>();
    if (total >= EXPIRY_MIN_TOTAL && (double) expired / total > EXPIRY_RATIO) {
      out.add(new AnomalyDto(AnomalyType.HIGH_EXPIRY, null, "MEDIUM",
          "expired " + expired + " of " + total + " terminal reservations", clock.instant()));
    }
    return out;
  }
}
