package com.sample.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@ConfigurationPropertiesScan
@EnableCaching
@EnableScheduling
@SpringBootApplication
public class InventoryOrderManagementSystemApplication {

  public static void main(String[] args) {
    SpringApplication.run(InventoryOrderManagementSystemApplication.class, args);
  }
}
