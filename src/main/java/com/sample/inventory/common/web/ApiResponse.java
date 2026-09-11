package com.sample.inventory.common.web;

public record ApiResponse<T>(T data, ApiError error) {

  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(data, null);
  }

  public static <T> ApiResponse<T> fail(String code, String message) {
    return new ApiResponse<>(null, new ApiError(code, message));
  }

  public record ApiError(String code, String message) {}
}
