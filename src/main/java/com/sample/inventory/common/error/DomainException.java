package com.sample.inventory.common.error;

public class DomainException extends RuntimeException {

  private final ErrorCode code;

  public DomainException(ErrorCode code, Object... args) {
    super(code.format(args));
    this.code = code;
  }

  public ErrorCode getCode() {
    return code;
  }
}
