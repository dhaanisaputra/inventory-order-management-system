package com.sample.inventory.common.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

class RedisCacheConfigTest {

  @Test
  @SuppressWarnings("unchecked")
  void valueSerializerRoundTrips() {
    SerializationPair<Object> pair =
        new RedisCacheConfig().cacheConfiguration().getValueSerializationPair();
    var original = Map.of("id", 7L, "sku", "KB-100", "active", true);
    var back = (Map<String, Object>) pair.read(pair.write(original));
    assertThat(back).containsEntry("sku", "KB-100");
  }
}
