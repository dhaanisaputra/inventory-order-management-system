package com.sample.inventory.returns;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ReturnLineRepository extends JpaRepository<ReturnLine, Long> {

  @Query("select coalesce(sum(l.qty), 0) from ReturnLine l where l.allocation.id = :allocationId")
  int sumReturnedByAllocation(long allocationId);
}
