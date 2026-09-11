package com.sample.inventory.returns;

public final class ReturnMapper {

  private ReturnMapper() {}

  public static ReturnResponse toResponse(ReturnOrder o) {
    return new ReturnResponse(o.getId(),
        o.getLines().stream().map(l -> new ReturnResponse.ReturnLineDto(l.getId(),
            l.getAllocation().getId(),
            l.getAllocation().getOrderLine().getProduct().getId(),
            l.getAllocation().getOrderLine().getProduct().getSku(),
            l.getAllocation().getWarehouse().getId(),
            l.getAllocation().getWarehouse().getCode(),
            l.getQty())).toList(),
        o.getCreatedAt());
  }
}
