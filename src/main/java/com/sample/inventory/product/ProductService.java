package com.sample.inventory.product;

import com.sample.inventory.common.error.DuplicateException;
import com.sample.inventory.common.error.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

  private final ProductRepository repo;

  @Transactional
  public ProductResponse create(ProductRequest req) {
    if (repo.existsBySku(req.sku())) {
      throw new DuplicateException("product", req.sku());
    }
    return ProductMapper.toResponse(repo.save(new Product(req.sku(), req.name())));
  }

  public ProductResponse get(Long id) {
    return repo.findById(id).map(ProductMapper::toResponse)
        .orElseThrow(() -> new NotFoundException("product", id));
  }

  public Page<ProductResponse> search(String q, Pageable pageable) {
    Page<Product> page =
        (q == null || q.isBlank()) ? repo.findAll(pageable) : repo.search(q, pageable);
    return page.map(ProductMapper::toResponse);
  }

  @Transactional
  public ProductResponse update(Long id, UpdateProductRequest req) {
    Product p = repo.findById(id).orElseThrow(() -> new NotFoundException("product", id));
    if (req.name() != null) {
      p.rename(req.name());
    }
    if (Boolean.FALSE.equals(req.active())) {
      p.deactivate();
    }
    return ProductMapper.toResponse(p);
  }
}
