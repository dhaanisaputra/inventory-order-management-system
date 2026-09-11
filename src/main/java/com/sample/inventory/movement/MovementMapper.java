package com.sample.inventory.movement;

public final class MovementMapper {

  private MovementMapper() {}

  public static MovementResponse toResponse(StockMovement m) {
    return new MovementResponse(
        m.getId(),
        m.getProduct().getId(),
        m.getProduct().getSku(),
        m.getWarehouse().getId(),
        m.getWarehouse().getCode(),
        m.getType(),
        m.getQty(),
        m.getRefType(),
        m.getRefId(),
        m.getCreatedAt());
  }
}
