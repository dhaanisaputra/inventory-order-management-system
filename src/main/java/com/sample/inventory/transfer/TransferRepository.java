package com.sample.inventory.transfer;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferRepository extends JpaRepository<StockTransfer, Long> {
}
