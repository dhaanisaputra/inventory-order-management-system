package com.sample.inventory.common.error;

public class OverReceiveException extends DomainException {

  public OverReceiveException(long lineId) {
    super(ErrorCode.OVER_RECEIVE, lineId);
  }
}
