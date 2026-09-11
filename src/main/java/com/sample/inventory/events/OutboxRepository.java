package com.sample.inventory.events;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

  @Query(
      value =
          "SELECT * FROM outbox WHERE published_at IS NULL"
              + " ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED",
      nativeQuery = true)
  List<OutboxEvent> lockUnpublished(int limit);
}
