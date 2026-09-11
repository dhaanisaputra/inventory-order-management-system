package com.sample.inventory.inventory;

public final class InventoryMapper {

  private InventoryMapper() {}

  public static InventoryResponse toResponse(Inventory i) {
    return new InventoryResponse(
        i.getId(),
        i.getProduct().getId(),
        i.getProduct().getSku(),
        i.getWarehouse().getId(),
        i.getWarehouse().getCode(),
        i.getAvailable(),
        i.getReserved(),
        i.getLowStockThreshold(),
        i.isLowStock());
  }
}
