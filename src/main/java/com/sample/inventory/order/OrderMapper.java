package com.sample.inventory.order;

public final class OrderMapper {

  private OrderMapper() {}

  public static OrderResponse toResponse(SalesOrder o) {
    return new OrderResponse(o.getId(), o.getStatus(),
        o.getLines().stream().map(l -> new OrderResponse.OrderLineDto(l.getId(),
            l.getProduct().getId(), l.getProduct().getSku(), l.getQty(),
            l.getAllocations().stream().map(a -> new OrderResponse.AllocationDto(a.getId(),
                a.getWarehouse().getId(), a.getWarehouse().getCode(), a.getQty())).toList()))
            .toList(),
        o.getCreatedAt());
  }
}
