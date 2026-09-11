package com.sample.inventory.common.error;

public class ReturnExceededException extends DomainException {

  public ReturnExceededException(long allocationId) {
    super(ErrorCode.RETURN_EXCEEDED, allocationId);
  }
}
