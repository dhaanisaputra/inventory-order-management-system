package com.sample.inventory.common.error;

public class NotFoundException extends DomainException {

  public NotFoundException(String resource, Object id) {
    super(ErrorCode.NOT_FOUND, resource + " " + id);
  }
}
