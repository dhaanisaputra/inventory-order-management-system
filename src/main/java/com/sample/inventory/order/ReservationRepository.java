package com.sample.inventory.order;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

  @Query(value = "SELECT * FROM reservation WHERE status = 'ACTIVE' AND expires_at <= :now"
      + " ORDER BY expires_at LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
  List<Reservation> lockDue(Instant now, int limit);
}
