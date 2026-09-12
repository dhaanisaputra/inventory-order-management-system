package com.sample.inventory.insights.assistant;

public interface AssistantProvider {

  AssistantResponse answer(String query);
}
