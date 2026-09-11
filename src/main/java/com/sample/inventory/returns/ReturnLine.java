package com.sample.inventory.returns;

import com.sample.inventory.order.Allocation;
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
@Table(name = "return_line")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReturnLine {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "return_order_id", nullable = false)
  private ReturnOrder order;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "allocation_id", nullable = false)
  private Allocation allocation;

  @Column(nullable = false)
  private int qty;

  public ReturnLine(ReturnOrder order, Allocation allocation, int qty) {
    this.order = order;
    this.allocation = allocation;
    this.qty = qty;
  }
}
