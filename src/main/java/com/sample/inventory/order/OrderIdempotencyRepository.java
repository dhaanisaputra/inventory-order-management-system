package com.sample.inventory.order;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderIdempotencyRepository extends JpaRepository<OrderIdempotency, String> {}
