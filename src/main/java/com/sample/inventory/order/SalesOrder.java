package com.sample.inventory.order;

import com.sample.inventory.common.error.InvalidTransitionException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "sales_order")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SalesOrder {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private OrderStatus status = OrderStatus.PENDING;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<OrderLine> lines = new ArrayList<>();

  public void addLine(OrderLine line) {
    lines.add(line);
  }

  public void confirm() {
    if (status != OrderStatus.PENDING) {
      throw new InvalidTransitionException(id, status);
    }
    status = OrderStatus.CONFIRMED;
  }

  public void cancel() {
    if (status != OrderStatus.PENDING) {
      throw new InvalidTransitionException(id, status);
    }
    status = OrderStatus.CANCELLED;
  }
}
