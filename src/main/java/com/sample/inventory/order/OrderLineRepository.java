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

  @Query("select l2.product.id, count(l2) from OrderLine l1 join OrderLine l2"
      + " on l2.order = l1.order where l1.product.id = :productId"
      + " and l2.product.id <> :productId and l1.order.status = :status"
      + " and l2.order.status = :status group by l2.product.id"
      + " having count(l2) >= :minSupport order by count(l2) desc")
  List<Object[]> countBasketMates(long productId, OrderStatus status, long minSupport);
}
