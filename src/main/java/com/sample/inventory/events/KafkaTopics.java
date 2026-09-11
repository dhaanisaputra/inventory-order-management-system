package com.sample.inventory.events;

public final class KafkaTopics {

  private KafkaTopics() {}

  public static final String ORDER_CREATED = "inventory.order.created";
  public static final String ORDER_CONFIRMED = "inventory.order.confirmed";
  public static final String ORDER_CANCELLED = "inventory.order.cancelled";
  public static final String STOCK_LOW = "inventory.stock.low";
  public static final String STOCK_MOVEMENT = "inventory.stock.movement";
}
