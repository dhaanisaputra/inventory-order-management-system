package com.sample.inventory.order;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

  @Query(
      value =
          "SELECT * FROM reservation WHERE status = 'ACTIVE' AND expires_at <= :now"
              + " ORDER BY expires_at LIMIT :limit FOR UPDATE SKIP LOCKED",
      nativeQuery = true)
  List<Reservation> lockDue(Instant now, int limit);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
  @Query("select r from Reservation r where r.allocation.orderLine.order.id = :orderId")
  List<Reservation> lockByOrderId(long orderId);

  boolean existsByAllocationOrderLineOrderIdAndStatus(long orderId, ReservationStatus status);

  long countByStatus(ReservationStatus status);
}
