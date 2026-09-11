package com.sample.inventory.events;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 128)
  private String topic;

  @Column(name = "event_key", nullable = false, length = 128)
  private String eventKey;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String payload;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "published_at")
  private Instant publishedAt;

  public OutboxEvent(String topic, String eventKey, String payload) {
    this.topic = topic;
    this.eventKey = eventKey;
    this.payload = payload;
  }

  public void markPublished(Instant at) {
    this.publishedAt = at;
  }
}
