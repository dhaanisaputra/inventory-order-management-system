package com.sample.inventory.transfer;

import com.sample.inventory.common.error.DomainException;
import com.sample.inventory.common.error.ErrorCode;
import com.sample.inventory.common.error.InsufficientStockException;
import com.sample.inventory.common.error.NotFoundException;
import com.sample.inventory.events.LowStockNotifier;
import com.sample.inventory.inventory.Inventory;
import com.sample.inventory.inventory.InventoryRepository;
import com.sample.inventory.movement.MovementType;
import com.sample.inventory.movement.MovementWriter;
import com.sample.inventory.product.Product;
import com.sample.inventory.product.ProductRepository;
import com.sample.inventory.warehouse.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TransferService {

  private final TransferRepository transferRepo;
  private final InventoryRepository invRepo;
  private final ProductRepository productRepo;
  private final WarehouseRepository warehouseRepo;
  private final MovementWriter movements;
  private final LowStockNotifier notifier;

  @Transactional
  @CacheEvict(value = "inv", allEntries = true)
  public TransferResponse create(TransferRequest req) {
    if (req.fromWarehouseId().equals(req.toWarehouseId())) {
      throw new DomainException(ErrorCode.VALIDATION);
    }
    Product product =
        productRepo
            .findById(req.productId())
            .orElseThrow(() -> new NotFoundException("product", req.productId()));
    long firstId = Math.min(req.fromWarehouseId(), req.toWarehouseId());
    long secondId = Math.max(req.fromWarehouseId(), req.toWarehouseId());
    var first = invRepo.lockOne(req.productId(), firstId);
    var second = invRepo.lockOne(req.productId(), secondId);
    boolean fromIsFirst = req.fromWarehouseId() == firstId;
    var sourceOpt = fromIsFirst ? first : second;
    var destOpt = fromIsFirst ? second : first;
    var source = sourceOpt.orElseThrow(() -> new InsufficientStockException(product.getSku()));
    if (source.getAvailable() < req.qty()) {
      throw new InsufficientStockException(product.getSku());
    }
    Inventory dest =
        destOpt.orElseGet(
            () -> {
              var wh =
                  warehouseRepo
                      .findById(fromIsFirst ? secondId : firstId)
                      .orElseThrow(
                          () ->
                              new NotFoundException("warehouse", fromIsFirst ? secondId : firstId));
              return invRepo.save(new Inventory(product, wh, 0, 0, 0));
            });
    source.deduct(req.qty());
    dest.add(req.qty());
    var transfer =
        transferRepo.saveAndFlush(
            new StockTransfer(product, source.getWarehouse(), dest.getWarehouse(), req.qty()));
    movements.write(
        product, source.getWarehouse(), MovementType.OUT, req.qty(), "TRANSFER", transfer.getId());
    movements.write(
        product, dest.getWarehouse(), MovementType.IN, req.qty(), "TRANSFER", transfer.getId());
    notifier.notifyIfLow(source);
    return TransferMapper.toResponse(transfer);
  }

  public TransferResponse get(long id) {
    return transferRepo
        .findById(id)
        .map(TransferMapper::toResponse)
        .orElseThrow(() -> new NotFoundException("transfer", id));
  }

  public Page<TransferResponse> list(Pageable pageable) {
    return transferRepo.findAll(pageable).map(TransferMapper::toResponse);
  }
}
