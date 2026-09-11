package com.sample.inventory.movement;

import com.sample.inventory.product.Product;
import com.sample.inventory.warehouse.Warehouse;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "stock_movement")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockMovement {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "product_id", nullable = false)
  private Product product;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "warehouse_id", nullable = false)
  private Warehouse warehouse;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private MovementType type;

  @Column(nullable = false)
  private int qty;

  @Column(name = "ref_type", nullable = false, length = 32)
  private String refType;

  @Column(name = "ref_id", nullable = false)
  private long refId;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public static StockMovement of(
      Product product,
      Warehouse warehouse,
      MovementType type,
      int qty,
      String refType,
      long refId) {
    StockMovement m = new StockMovement();
    m.product = product;
    m.warehouse = warehouse;
    m.type = type;
    m.qty = qty;
    m.refType = refType;
    m.refId = refId;
    return m;
  }
}
