package com.sample.inventory.warehouse;

import com.sample.inventory.common.error.DuplicateException;
import com.sample.inventory.common.error.NotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WarehouseService {

  private final WarehouseRepository repo;

  @Transactional
  public WarehouseResponse create(WarehouseRequest req) {
    if (repo.existsByCode(req.code())) {
      throw new DuplicateException("warehouse", req.code());
    }
    try {
      return WarehouseMapper.toResponse(
          repo.save(new Warehouse(req.code(), req.name(), req.priority())));
    } catch (DataIntegrityViolationException e) {
      throw new DuplicateException("warehouse", req.code());
    }
  }

  public WarehouseResponse get(Long id) {
    return repo.findById(id)
        .map(WarehouseMapper::toResponse)
        .orElseThrow(() -> new NotFoundException("warehouse", id));
  }

  public List<WarehouseResponse> list() {
    return repo.findAll(Sort.by("priority").ascending()).stream()
        .map(WarehouseMapper::toResponse)
        .toList();
  }

  @Transactional
  public WarehouseResponse update(Long id, UpdateWarehouseRequest req) {
    Warehouse w = repo.findById(id).orElseThrow(() -> new NotFoundException("warehouse", id));
    if (req.name() != null && !req.name().isBlank()) {
      w.rename(req.name().strip());
    }
    if (req.priority() != null) {
      w.reprioritize(req.priority());
    }
    return WarehouseMapper.toResponse(w);
  }
}
