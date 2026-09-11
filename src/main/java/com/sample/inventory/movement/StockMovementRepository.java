package com.sample.inventory.movement;

import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

  @EntityGraph(attributePaths = {"product", "warehouse"})
  @Query("select m from StockMovement m where (:productId is null or m.product.id = :productId)"
      + " and (:warehouseId is null or m.warehouse.id = :warehouseId)"
      + " and (:type is null or m.type = :type)"
      + " and (:from is null or m.createdAt >= :from)"
      + " and (:to is null or m.createdAt <= :to)")
  Page<StockMovement> search(Long productId, Long warehouseId, MovementType type,
      Instant from, Instant to, Pageable pageable);
}
