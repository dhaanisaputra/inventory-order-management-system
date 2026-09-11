package com.sample.inventory.order;

import com.sample.inventory.common.error.NotFoundException;
import com.sample.inventory.inventory.InventoryRepository;
import com.sample.inventory.movement.MovementType;
import com.sample.inventory.movement.MovementWriter;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReservationExpiryService {

  private final ReservationRepository reservationRepo;
  private final InventoryRepository invRepo;
  private final MovementWriter movements;

  @Transactional
  public int expireBatch(Instant now) {
    var due = reservationRepo.lockDue(now, 500);
    for (var r : due) {
      var alloc = r.getAllocation();
      var line = alloc.getOrderLine();
      var inv = invRepo.lockOne(line.getProduct().getId(), alloc.getWarehouse().getId())
          .orElseThrow(() -> new NotFoundException("inventory",
              line.getProduct().getId() + "/" + alloc.getWarehouse().getId()));
      inv.release(r.getQty());
      r.expire();
      movements.write(line.getProduct(), alloc.getWarehouse(), MovementType.RELEASE,
          r.getQty(), "ORDER", line.getOrder().getId());
    }
    return due.size();
  }
}
