package com.sample.inventory.product;

public final class ProductMapper {

  private ProductMapper() {}

  public static ProductResponse toResponse(Product p) {
    return new ProductResponse(p.getId(), p.getSku(), p.getName(), p.isActive());
  }
}
