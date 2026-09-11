package com.sample.inventory.order;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;

public final class RequestHash {

  private RequestHash() {}

  public static String of(CreateOrderRequest req) {
    try {
      var canonical =
          req.lines().stream()
              .sorted(Comparator.comparing(CreateOrderRequest.CreateOrderLine::productId))
              .map(l -> l.productId() + ":" + l.qty())
              .reduce((a, b) -> a + "|" + b)
              .orElse("");
      var digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (Exception e) {
      throw new IllegalStateException("hash failed", e);
    }
  }
}
