package com.sample.inventory.common.web;

import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public final class SortValidator {

  private SortValidator() {}

  public static Pageable validated(Pageable pageable, Set<String> allowed, Sort defaultSort) {
    boolean ok = pageable.getSort().isSorted()
        && pageable.getSort().stream().map(Sort.Order::getProperty).allMatch(allowed::contains);
    if (ok) {
      return pageable;
    }
    return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), defaultSort);
  }
}
