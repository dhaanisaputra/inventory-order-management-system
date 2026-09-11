package com.sample.inventory.order;

import com.sample.inventory.common.error.InvalidTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "reservation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @OneToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "allocation_id", nullable = false, unique = true)
  private Allocation allocation;

  @Column(nullable = false)
  private int qty;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private ReservationStatus status = ReservationStatus.ACTIVE;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public Reservation(Allocation allocation, int qty, Instant expiresAt) {
    this.allocation = allocation;
    this.qty = qty;
    this.expiresAt = expiresAt;
  }

  public void confirm() {
    requireActive();
    status = ReservationStatus.CONFIRMED;
  }

  public void cancel() {
    requireActive();
    status = ReservationStatus.CANCELLED;
  }

  public void expire() {
    requireActive();
    status = ReservationStatus.EXPIRED;
  }

  private void requireActive() {
    if (status != ReservationStatus.ACTIVE) {
      throw new InvalidTransitionException(null, status);
    }
  }
}
