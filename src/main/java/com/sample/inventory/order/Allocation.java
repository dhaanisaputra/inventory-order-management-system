package com.sample.inventory.order;

import com.sample.inventory.warehouse.Warehouse;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "allocation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Allocation {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "order_line_id", nullable = false)
  private OrderLine orderLine;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "warehouse_id", nullable = false)
  private Warehouse warehouse;

  @Column(nullable = false)
  private int qty;

  @OneToOne(mappedBy = "allocation", fetch = FetchType.LAZY)
  private Reservation reservation;

  public Allocation(OrderLine orderLine, Warehouse warehouse, int qty) {
    this.orderLine = orderLine;
    this.warehouse = warehouse;
    this.qty = qty;
  }

  void linkReservation(Reservation reservation) {
    this.reservation = reservation;
  }
}
