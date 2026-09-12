package com.sample.inventory.insights.assistant;

import jakarta.validation.constraints.NotBlank;

public record AssistantRequest(@NotBlank String query) {}
