package com.sample.inventory.transfer;

import com.sample.inventory.product.Product;
import com.sample.inventory.warehouse.Warehouse;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "stock_transfer")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockTransfer {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "product_id", nullable = false)
  private Product product;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "from_warehouse_id", nullable = false)
  private Warehouse fromWarehouse;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "to_warehouse_id", nullable = false)
  private Warehouse toWarehouse;

  @Column(nullable = false)
  private int qty;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public StockTransfer(Product product, Warehouse fromWarehouse, Warehouse toWarehouse, int qty) {
    this.product = product;
    this.fromWarehouse = fromWarehouse;
    this.toWarehouse = toWarehouse;
    this.qty = qty;
  }
}
