package com.sample.inventory.order;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SalesOrderRepository extends JpaRepository<SalesOrder, Long> {

  @EntityGraph(attributePaths = {"lines", "lines.product"})
  @Query("select o from SalesOrder o where o.id = :id")
  Optional<SalesOrder> findDetailedById(long id);

  @Query("select o from SalesOrder o where (:status is null or o.status = :status)"
      + " and (:from is null or o.createdAt >= :from)"
      + " and (:to is null or o.createdAt <= :to)")
  Page<SalesOrder> search(OrderStatus status, Instant from, Instant to, Pageable pageable);
}
