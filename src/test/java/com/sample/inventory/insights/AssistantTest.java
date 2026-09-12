package com.sample.inventory.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.sample.inventory.insights.assistant.RuleBasedAssistantProvider;
import com.sample.inventory.insights.replenishment.ReplenishmentService;
import com.sample.inventory.insights.replenishment.ReplenishmentSuggestion;
import com.sample.inventory.inventory.InventoryResponse;
import com.sample.inventory.inventory.InventoryService;
import com.sample.inventory.order.OrderResponse;
import com.sample.inventory.order.OrderService;
import com.sample.inventory.order.OrderStatus;
import com.sample.inventory.product.ProductResponse;
import com.sample.inventory.product.ProductService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class AssistantTest {

  @Mock ProductService products;
  @Mock InventoryService inventories;
  @Mock OrderService orders;
  @Mock ReplenishmentService replenishment;
  @InjectMocks RuleBasedAssistantProvider assistant;

  @Test
  void answersStockQuery() {
    when(products.search(anyString(), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(new ProductResponse(1L, "KB-100", "Keyboard", true))));
    when(inventories.getByProduct(1L))
        .thenReturn(List.of(new InventoryResponse(9L, 1L, "KB-100", 2L, "JKT-1", 5, 0, 2, false)));
    var out = assistant.answer("stok KB-100");
    assertThat(out.intent()).isEqualTo("stock_lookup");
    assertThat(out.answer()).contains("KB-100").contains("5");
  }

  @Test
  void answersLowStock() {
    when(inventories.search(any(), any(), anyBoolean(), any(Pageable.class)))
        .thenReturn(
            new PageImpl<>(
                List.of(new InventoryResponse(9L, 1L, "KB-100", 2L, "JKT-1", 1, 0, 5, true))));
    var out = assistant.answer("low stock apa saja?");
    assertThat(out.intent()).isEqualTo("low_stock");
    assertThat(out.answer()).contains("KB-100");
  }

  @Test
  void answersOrderStatus() {
    when(orders.get(7L))
        .thenReturn(
            new OrderResponse(
                7L, OrderStatus.CONFIRMED, List.of(), Instant.parse("2026-09-11T00:00:00Z")));
    var out = assistant.answer("status order 7");
    assertThat(out.intent()).isEqualTo("order_status");
    assertThat(out.answer()).contains("7").contains("CONFIRMED");
  }

  @Test
  void answersReplenishment() {
    when(replenishment.suggestAll())
        .thenReturn(List.of(new ReplenishmentSuggestion(1L, "KB-100", 2, 10.0, 8, "low")));
    var out = assistant.answer("minta restock");
    assertThat(out.intent()).isEqualTo("replenishment");
    assertThat(out.answer()).contains("KB-100").contains("8");
  }

  @Test
  void unknownFallsBackToHelp() {
    var out = assistant.answer("halo apa kabar");
    assertThat(out.intent()).isEqualTo("help");
    assertThat(out.answer()).contains("bisa");
  }
}
