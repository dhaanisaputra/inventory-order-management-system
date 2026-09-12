package com.sample.inventory.transfer;

public final class TransferMapper {

  private TransferMapper() {}

  public static TransferResponse toResponse(StockTransfer t) {
    return new TransferResponse(
        t.getId(),
        t.getProduct().getId(),
        t.getProduct().getSku(),
        t.getFromWarehouse().getId(),
        t.getFromWarehouse().getCode(),
        t.getToWarehouse().getId(),
        t.getToWarehouse().getCode(),
        t.getQty(),
        t.getCreatedAt());
  }
}
