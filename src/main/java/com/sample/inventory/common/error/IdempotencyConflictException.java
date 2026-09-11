package com.sample.inventory.common.error;

public class IdempotencyConflictException extends DomainException {

  public IdempotencyConflictException() {
    super(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
  }
}
