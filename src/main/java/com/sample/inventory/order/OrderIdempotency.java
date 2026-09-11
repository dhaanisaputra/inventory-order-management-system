package com.sample.inventory.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
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
@Table(name = "order_idempotency")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderIdempotency {

  @Id
  @Column(name = "idem_key", nullable = false, length = 64)
  private String key;

  @Column(name = "request_hash", nullable = false, length = 64)
  private String requestHash;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "order_id", nullable = false)
  private SalesOrder order;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public OrderIdempotency(String key, String requestHash, SalesOrder order) {
    this.key = key;
    this.requestHash = requestHash;
    this.order = order;
  }
}
