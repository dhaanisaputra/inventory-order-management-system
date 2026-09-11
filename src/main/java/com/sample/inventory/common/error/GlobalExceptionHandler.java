package com.sample.inventory.common.error;

import com.sample.inventory.common.web.ApiResponse;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(DomainException.class)
  public ResponseEntity<ApiResponse<Void>> handleDomain(DomainException ex) {
    ErrorCode code = ex.getCode();
    return ResponseEntity.status(code.httpStatus())
        .body(ApiResponse.fail(code.name(), ex.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
    String message =
        ex.getBindingResult().getFieldErrors().stream()
            .map(e -> e.getField() + " " + e.getDefaultMessage())
            .collect(Collectors.joining("; "));
    return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.VALIDATION.name(), message));
  }

  @ExceptionHandler({PessimisticLockException.class, LockTimeoutException.class})
  public ResponseEntity<ApiResponse<Void>> handleContention(RuntimeException ex) {
    return ResponseEntity.status(ErrorCode.STOCK_CONTENTION.httpStatus())
        .body(
            ApiResponse.fail(
                ErrorCode.STOCK_CONTENTION.name(), ErrorCode.STOCK_CONTENTION.format()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> handleFallback(Exception ex) {
    return ResponseEntity.internalServerError()
        .body(ApiResponse.fail(ErrorCode.INTERNAL.name(), ErrorCode.INTERNAL.format()));
  }
}
