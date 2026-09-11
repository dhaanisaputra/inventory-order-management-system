package com.sample.inventory.common.error;

public class InsufficientStockException extends DomainException {

  public InsufficientStockException(String sku) {
    super(ErrorCode.INSUFFICIENT_STOCK, sku);
  }
}
