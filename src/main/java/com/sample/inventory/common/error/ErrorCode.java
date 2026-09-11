package com.sample.inventory.common.error;

public enum ErrorCode {
  VALIDATION(400, "validation failed"),
  NOT_FOUND(404, "resource not found"),
  DUPLICATE(409, "{0} already exists"),
  INTERNAL(500, "internal error"),
  INSUFFICIENT_STOCK(409, "stock insufficient for product {0}"),
  STOCK_CONTENTION(409, "stock contention, please retry"),
  INVALID_TRANSITION(409, "order {0} cannot transition from {1}"),
  IDEMPOTENCY_KEY_CONFLICT(422, "idempotency key reused with different payload"),
  OVER_RECEIVE(400, "over-receive on purchase line {0}");

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
