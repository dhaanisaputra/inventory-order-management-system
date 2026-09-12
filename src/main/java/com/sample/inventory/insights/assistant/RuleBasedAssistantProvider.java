package com.sample.inventory.insights.assistant;

import com.sample.inventory.insights.replenishment.ReplenishmentService;
import com.sample.inventory.inventory.InventoryService;
import com.sample.inventory.order.OrderService;
import com.sample.inventory.product.ProductService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class RuleBasedAssistantProvider implements AssistantProvider {

  private static final Pattern ORDER_ID = Pattern.compile("order\\s+(\\d+)");
  private static final Pattern STOCK_SKU = Pattern.compile("sto[kc]\\s+(\\S+)");

  private final ProductService products;
  private final InventoryService inventories;
  private final OrderService orders;
  private final ReplenishmentService replenishment;

  @Override
  public AssistantResponse answer(String query) {
    String q = query == null ? "" : query.trim().toLowerCase();
    Matcher orderM = ORDER_ID.matcher(q);
    if (orderM.find()) {
      long id = Long.parseLong(orderM.group(1));
      var o = orders.get(id);
      return new AssistantResponse(
          "order_status",
          "Order " + o.id() + " is " + o.status() + " (" + o.lines().size() + " lines)");
    }
    if (q.contains("low stock") || q.contains("stok menipis") || q.contains("stok rendah")) {
      var page = inventories.search(null, null, true, PageRequest.of(0, 5));
      if (page.isEmpty()) {
        return new AssistantResponse("low_stock", "Tidak ada stok menipis. Semua aman.");
      }
      String items =
          page.getContent().stream()
              .limit(5)
              .map(i -> i.productSku() + "=" + i.available())
              .collect(Collectors.joining(", "));
      return new AssistantResponse(
          "low_stock", "Stok menipis (" + page.getTotalElements() + "): " + items);
    }
    Matcher stockM = STOCK_SKU.matcher(q);
    if (stockM.find()) {
      String sku = stockM.group(1).toUpperCase();
      var matches = products.search(sku, PageRequest.of(0, 5)).getContent();
      if (matches.isEmpty()) {
        return new AssistantResponse("stock_lookup", "Produk " + sku + " tidak ditemukan.");
      }
      var prod = matches.get(0);
      var rows = inventories.getByProduct(prod.id());
      int total = rows.stream().mapToInt(r -> r.available()).sum();
      String detail =
          rows.stream()
              .map(r -> r.warehouseCode() + "=" + r.available())
              .collect(Collectors.joining(", "));
      return new AssistantResponse(
          "stock_lookup", prod.sku() + ": " + total + " available (" + detail + ")");
    }
    if (q.contains("replenish")
        || q.contains("restock")
        || q.contains("isi ulang")
        || q.contains("reorder")) {
      var top = replenishment.suggestAll().stream().limit(3).toList();
      if (top.isEmpty()) {
        return new AssistantResponse("replenishment", "Tidak ada yang perlu restock.");
      }
      String items =
          top.stream()
              .map(
                  s ->
                      s.productSku()
                          + " suggest "
                          + s.suggestedQty()
                          + " (avail "
                          + s.available()
                          + ")")
              .collect(Collectors.joining("; "));
      return new AssistantResponse("replenishment", "Restock: " + items);
    }
    return new AssistantResponse(
        "help",
        "Saya bisa: stok <SKU>, low stock, status order <id>, restock. Coba salah satunya.");
  }
}
