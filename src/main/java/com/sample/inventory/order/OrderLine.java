package com.sample.inventory.order;

import com.sample.inventory.product.Product;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;

@Entity
@Table(name = "order_line")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderLine {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "sales_order_id", nullable = false)
  private SalesOrder order;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "product_id", nullable = false)
  private Product product;

  @Column(nullable = false)
  private int qty;

  @BatchSize(size = 50)
  @OneToMany(mappedBy = "orderLine", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<Allocation> allocations = new ArrayList<>();

  public OrderLine(SalesOrder order, Product product, int qty) {
    this.order = order;
    this.product = product;
    this.qty = qty;
  }

  public void addAllocation(Allocation allocation) {
    allocations.add(allocation);
  }
}
