package com.sample.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PostgresSmokeIT {

  @Autowired PostgreSQLContainer postgres;

  @Test
  void containerIsRunning() {
    assertThat(postgres.isRunning()).isTrue();
  }
}
