package com.sample.inventory.purchase;

import com.sample.inventory.common.error.OverReceiveException;
import com.sample.inventory.product.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "purchase_order_line")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PurchaseOrderLine {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "purchase_order_id", nullable = false)
  private PurchaseOrder order;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "product_id", nullable = false)
  private Product product;

  @Column(name = "ordered_qty", nullable = false)
  private int orderedQty;

  @Column(name = "received_qty", nullable = false)
  private int receivedQty;

  public PurchaseOrderLine(PurchaseOrder order, Product product, int orderedQty) {
    this.order = order;
    this.product = product;
    this.orderedQty = orderedQty;
  }

  public int remaining() {
    return orderedQty - receivedQty;
  }

  public void receive(int qty) {
    if (qty <= 0 || receivedQty + qty > orderedQty) {
      throw new OverReceiveException(id);
    }
    receivedQty += qty;
  }
}
