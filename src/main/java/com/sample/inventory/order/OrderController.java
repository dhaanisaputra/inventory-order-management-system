package com.sample.inventory.order;

import com.sample.inventory.common.error.DomainException;
import com.sample.inventory.common.error.ErrorCode;
import com.sample.inventory.common.web.ApiResponse;
import com.sample.inventory.common.web.PagedResult;
import com.sample.inventory.common.web.SortValidator;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

  private static final Set<String> SORTABLE = Set.of("createdAt");
  private static final Sort DEFAULT_SORT = Sort.by("createdAt").descending();

  private final OrderService service;

  @PostMapping
  public ResponseEntity<ApiResponse<OrderResponse>> create(
      @Valid @RequestBody CreateOrderRequest req,
      @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
    var res = service.create(req, idemKey, idemKey == null ? null : RequestHash.of(req));
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(res));
  }

  @GetMapping("/{id}")
  public ResponseEntity<ApiResponse<OrderResponse>> get(@PathVariable long id) {
    return ResponseEntity.ok(ApiResponse.ok(service.get(id)));
  }

  @GetMapping
  public ResponseEntity<ApiResponse<PagedResult<OrderResponse>>> search(
      @RequestParam(required = false) OrderStatus status,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      Pageable pageable) {
    if (from != null && to != null && from.isAfter(to)) {
      throw new DomainException(ErrorCode.VALIDATION);
    }
    var page =
        service.search(status, from, to, SortValidator.validated(pageable, SORTABLE, DEFAULT_SORT));
    return ResponseEntity.ok(ApiResponse.ok(PagedResult.from(page)));
  }

  @PostMapping("/{id}/confirm")
  public ResponseEntity<ApiResponse<OrderResponse>> confirm(@PathVariable long id) {
    return ResponseEntity.ok(ApiResponse.ok(service.confirm(id)));
  }

  @PostMapping("/{id}/cancel")
  public ResponseEntity<ApiResponse<OrderResponse>> cancel(@PathVariable long id) {
    return ResponseEntity.ok(ApiResponse.ok(service.cancel(id)));
  }
}
