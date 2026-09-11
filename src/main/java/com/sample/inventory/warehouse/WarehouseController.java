package com.sample.inventory.warehouse;

import com.sample.inventory.common.web.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/warehouses")
@RequiredArgsConstructor
public class WarehouseController {

  private final WarehouseService service;

  @PostMapping
  public ResponseEntity<ApiResponse<WarehouseResponse>> create(
      @Valid @RequestBody WarehouseRequest req) {
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.create(req)));
  }

  @GetMapping
  public ResponseEntity<ApiResponse<List<WarehouseResponse>>> list() {
    return ResponseEntity.ok(ApiResponse.ok(service.list()));
  }

  @GetMapping("/{id}")
  public ResponseEntity<ApiResponse<WarehouseResponse>> get(@PathVariable Long id) {
    return ResponseEntity.ok(ApiResponse.ok(service.get(id)));
  }

  @PatchMapping("/{id}")
  public ResponseEntity<ApiResponse<WarehouseResponse>> update(
      @PathVariable Long id, @Valid @RequestBody UpdateWarehouseRequest req) {
    return ResponseEntity.ok(ApiResponse.ok(service.update(id, req)));
  }
}
