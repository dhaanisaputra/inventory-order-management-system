package com.sample.inventory.inventory;

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
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "inventory")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inventory {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "product_id", nullable = false)
  private Product product;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "warehouse_id", nullable = false)
  private Warehouse warehouse;

  @Column(nullable = false)
  private int available;

  @Column(nullable = false)
  private int reserved;

  @Column(name = "low_stock_threshold", nullable = false)
  private int lowStockThreshold;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public Inventory(
      Product product, Warehouse warehouse, int available, int reserved, int lowStockThreshold) {
    this.product = product;
    this.warehouse = warehouse;
    this.available = available;
    this.reserved = reserved;
    this.lowStockThreshold = lowStockThreshold;
  }

  public void reserve(int qty) {
    if (qty <= 0 || qty > available) {
      throw new IllegalArgumentException("cannot reserve " + qty + ", available=" + available);
    }
    available -= qty;
    reserved += qty;
  }

  public void confirm(int qty) {
    if (qty <= 0 || qty > reserved) {
      throw new IllegalArgumentException("cannot confirm " + qty + ", reserved=" + reserved);
    }
    reserved -= qty;
  }

  public void release(int qty) {
    if (qty <= 0 || qty > reserved) {
      throw new IllegalArgumentException("cannot release " + qty + ", reserved=" + reserved);
    }
    reserved -= qty;
    available += qty;
  }

  public void deduct(int qty) {
    if (qty <= 0 || qty > available) {
      throw new IllegalArgumentException("cannot deduct " + qty + ", available=" + available);
    }
    available -= qty;
  }

  public void add(int qty) {
    if (qty <= 0) {
      throw new IllegalArgumentException("qty must be positive");
    }
    available += qty;
  }

  public boolean isLowStock() {
    return available < lowStockThreshold;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Inventory other)) {
      return false;
    }
    return id != null && Objects.equals(id, other.id);
  }

  @Override
  public int hashCode() {
    return getClass().hashCode();
  }
}
