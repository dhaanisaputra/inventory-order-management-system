package com.sample.inventory.order;

import com.sample.inventory.common.error.NotFoundException;
import com.sample.inventory.inventory.InventoryRepository;
import com.sample.inventory.movement.MovementType;
import com.sample.inventory.movement.MovementWriter;
import java.time.Instant;
import java.util.HashSet;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReservationExpiryService {

  private final ReservationRepository reservationRepo;
  private final InventoryRepository invRepo;
  private final MovementWriter movements;
  private final SalesOrderRepository orderRepo;

  @Transactional
  @CacheEvict(value = "inv", allEntries = true)
  public int expireBatch(Instant now) {
    var due = reservationRepo.lockDue(now, 500);
    for (var r : due) {
      var alloc = r.getAllocation();
      var line = alloc.getOrderLine();
      var inv =
          invRepo
              .lockOne(line.getProduct().getId(), alloc.getWarehouse().getId())
              .orElseThrow(
                  () ->
                      new NotFoundException(
                          "inventory",
                          line.getProduct().getId() + "/" + alloc.getWarehouse().getId()));
      inv.release(r.getQty());
      r.expire();
      movements.write(
          line.getProduct(),
          alloc.getWarehouse(),
          MovementType.RELEASE,
          r.getQty(),
          "ORDER",
          line.getOrder().getId());
    }
    var orderIds = new HashSet<Long>();
    for (var r : due) {
      orderIds.add(r.getAllocation().getOrderLine().getOrder().getId());
    }
    for (var orderId : orderIds) {
      var order = orderRepo.findById(orderId).orElse(null);
      if (order != null
          && order.getStatus() == OrderStatus.PENDING
          && !reservationRepo.existsByAllocationOrderLineOrderIdAndStatus(
              orderId, ReservationStatus.ACTIVE)) {
        order.cancel();
      }
    }
    return due.size();
  }
}
