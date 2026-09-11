package com.sample.inventory.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@ConditionalOnProperty(name = "app.kafka.topics.create", matchIfMissing = true)
public class KafkaTopicConfig {

  private NewTopic topic(String name) {
    return TopicBuilder.name(name).partitions(1).replicas(1).build();
  }

  @Bean
  NewTopic orderCreated() {
    return topic(KafkaTopics.ORDER_CREATED);
  }

  @Bean
  NewTopic orderConfirmed() {
    return topic(KafkaTopics.ORDER_CONFIRMED);
  }

  @Bean
  NewTopic orderCancelled() {
    return topic(KafkaTopics.ORDER_CANCELLED);
  }

  @Bean
  NewTopic stockMovement() {
    return topic(KafkaTopics.STOCK_MOVEMENT);
  }

  @Bean
  NewTopic stockLow() {
    return topic(KafkaTopics.STOCK_LOW);
  }
}
