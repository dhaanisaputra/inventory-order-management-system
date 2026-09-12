package com.sample.inventory.insights.replenishment;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.replenishment")
public record ReplenishmentProperties(int leadTimeDays, int safetyDays) {}
