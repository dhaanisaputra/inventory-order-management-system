package com.sample.inventory.transfer;

import com.sample.inventory.common.web.ApiResponse;
import com.sample.inventory.common.web.PagedResult;
import com.sample.inventory.common.web.SortValidator;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transfers")
@RequiredArgsConstructor
public class TransferController {

  private static final Set<String> SORTABLE = Set.of("createdAt");
  private static final Sort DEFAULT_SORT = Sort.by("createdAt").descending();

  private final TransferService service;

  @PostMapping
  public ResponseEntity<ApiResponse<TransferResponse>> create(
      @Valid @RequestBody TransferRequest req) {
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.create(req)));
  }

  @GetMapping
  public ResponseEntity<ApiResponse<PagedResult<TransferResponse>>> list(Pageable pageable) {
    var page = service.list(SortValidator.validated(pageable, SORTABLE, DEFAULT_SORT));
    return ResponseEntity.ok(ApiResponse.ok(PagedResult.from(page)));
  }

  @GetMapping("/{id}")
  public ResponseEntity<ApiResponse<TransferResponse>> get(@PathVariable long id) {
    return ResponseEntity.ok(ApiResponse.ok(service.get(id)));
  }
}
