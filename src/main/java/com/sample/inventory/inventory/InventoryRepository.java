package com.sample.inventory.inventory;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
  @Query(
      "select i from Inventory i where i.product.id = :productId and i.available > 0"
          + " order by i.warehouse.priority asc, i.warehouse.id asc")
  List<Inventory> lockAvailable(long productId);

  List<Inventory> findByProduct_Id(long productId);

  @EntityGraph(attributePaths = {"product", "warehouse"})
  List<Inventory> findByProductId(long productId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
  @Query(
      "select i from Inventory i where i.product.id = :productId and i.warehouse.id = :warehouseId")
  Optional<Inventory> lockOne(long productId, long warehouseId);

  @EntityGraph(attributePaths = {"product", "warehouse"})
  @Query(
      "select i from Inventory i where (:productId is null or i.product.id = :productId)"
          + " and (:warehouseId is null or i.warehouse.id = :warehouseId)"
          + " and (:lowOnly = false or i.available < i.lowStockThreshold)")
  Page<Inventory> search(Long productId, Long warehouseId, boolean lowOnly, Pageable pageable);
}
