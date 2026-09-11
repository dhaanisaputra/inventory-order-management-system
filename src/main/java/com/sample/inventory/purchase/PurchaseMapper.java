package com.sample.inventory.purchase;

public final class PurchaseMapper {

  private PurchaseMapper() {}

  public static PurchaseResponse toResponse(PurchaseOrder o) {
    return new PurchaseResponse(o.getId(), o.getStatus(),
        o.getLines().stream().map(l -> new PurchaseResponse.PurchaseLineDto(l.getId(),
            l.getProduct().getId(), l.getProduct().getSku(), l.getOrderedQty(), l.getReceivedQty()))
            .toList(),
        o.getCreatedAt());
  }
}
