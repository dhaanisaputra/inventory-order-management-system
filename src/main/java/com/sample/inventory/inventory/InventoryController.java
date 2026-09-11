package com.sample.inventory.inventory;

import com.sample.inventory.common.web.ApiResponse;
import com.sample.inventory.common.web.PagedResult;
import com.sample.inventory.common.web.SortValidator;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inventories")
@RequiredArgsConstructor
public class InventoryController {

  private static final Set<String> SORTABLE = Set.of("available", "reserved", "createdAt");
  private static final Sort DEFAULT_SORT = Sort.by("id").ascending();

  private final InventoryService service;

  @GetMapping
  public ResponseEntity<ApiResponse<PagedResult<InventoryResponse>>> search(
      @RequestParam(required = false) Long productId,
      @RequestParam(required = false) Long warehouseId,
      @RequestParam(defaultValue = "false") boolean lowStockOnly,
      Pageable pageable) {
    var page = service.search(productId, warehouseId, lowStockOnly,
        SortValidator.validated(pageable, SORTABLE, DEFAULT_SORT));
    return ResponseEntity.ok(ApiResponse.ok(PagedResult.from(page)));
  }

  @GetMapping("/{id}")
  public ResponseEntity<ApiResponse<InventoryResponse>> get(@PathVariable long id) {
    return ResponseEntity.ok(ApiResponse.ok(service.get(id)));
  }
}
