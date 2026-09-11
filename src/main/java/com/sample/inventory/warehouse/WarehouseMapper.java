package com.sample.inventory.warehouse;

public final class WarehouseMapper {

  private WarehouseMapper() {}

  public static WarehouseResponse toResponse(Warehouse w) {
    return new WarehouseResponse(w.getId(), w.getCode(), w.getName(), w.getPriority());
  }
}
