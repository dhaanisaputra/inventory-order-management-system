package com.sample.inventory.movement;

import com.sample.inventory.common.error.DomainException;
import com.sample.inventory.common.error.ErrorCode;
import com.sample.inventory.common.web.ApiResponse;
import com.sample.inventory.common.web.PagedResult;
import com.sample.inventory.common.web.SortValidator;
import java.time.Instant;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/stock-movements")
@RequiredArgsConstructor
public class MovementController {

  private static final Set<String> SORTABLE = Set.of("createdAt");
  private static final Sort DEFAULT_SORT = Sort.by("createdAt").descending();

  private final MovementService service;

  @GetMapping
  public ResponseEntity<ApiResponse<PagedResult<MovementResponse>>> search(
      @RequestParam(required = false) Long productId,
      @RequestParam(required = false) Long warehouseId,
      @RequestParam(required = false) MovementType type,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      Pageable pageable) {
    if (from != null && to != null && from.isAfter(to)) {
      throw new DomainException(ErrorCode.VALIDATION);
    }
    var page =
        service.search(
            productId,
            warehouseId,
            type,
            from,
            to,
            SortValidator.validated(pageable, SORTABLE, DEFAULT_SORT));
    return ResponseEntity.ok(ApiResponse.ok(PagedResult.from(page)));
  }
}
