package com.sample.inventory.product;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProductRepository extends JpaRepository<Product, Long> {

  Optional<Product> findBySku(String sku);

  boolean existsBySku(String sku);

  @Query("select p from Product p where lower(p.sku) like lower(concat('%', :q, '%'))"
      + " or lower(p.name) like lower(concat('%', :q, '%'))")
  Page<Product> search(String q, Pageable pageable);
}
