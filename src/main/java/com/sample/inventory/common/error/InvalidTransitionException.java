package com.sample.inventory.common.error;

public class InvalidTransitionException extends DomainException {

  public InvalidTransitionException(Object orderId, Object status) {
    super(ErrorCode.INVALID_TRANSITION, orderId, status);
  }
}
