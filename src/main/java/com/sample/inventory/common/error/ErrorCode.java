package com.sample.inventory.common.error;

public enum ErrorCode {
  VALIDATION(400, "validation failed"),
  NOT_FOUND(404, "resource not found"),
  DUPLICATE(409, "{0} already exists"),
  INTERNAL(500, "internal error");

  private final int httpStatus;
  private final String template;

  ErrorCode(int httpStatus, String template) {
    this.httpStatus = httpStatus;
    this.template = template;
  }

  public int httpStatus() {
    return httpStatus;
  }

  public String format(Object... args) {
    String out = template;
    for (int i = 0; i < args.length; i++) {
      out = out.replace("{" + i + "}", String.valueOf(args[i]));
    }
    return out;
  }
}
