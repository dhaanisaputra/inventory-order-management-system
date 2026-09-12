package com.sample.inventory.order;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OrderLineRepository extends JpaRepository<OrderLine, Long> {

  @EntityGraph(attributePaths = {"order"})
  @Query("select l from OrderLine l where l.product.id = :productId"
      + " and l.order.status = :status and l.order.createdAt >= :since"
      + " order by l.order.createdAt asc")
  List<OrderLine> findConfirmedByProductSince(long productId, OrderStatus status, Instant since);
}
