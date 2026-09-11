package com.sample.inventory.common.error;

public class DuplicateException extends DomainException {

  public DuplicateException(String resource, Object key) {
    super(ErrorCode.DUPLICATE, resource + " " + key);
  }
}
