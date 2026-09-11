package com.sample.inventory.returns;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReturnOrderRepository extends JpaRepository<ReturnOrder, Long> {
}
