package com.sample.inventory.purchase;

import com.sample.inventory.common.error.DomainException;
import com.sample.inventory.common.error.ErrorCode;
import com.sample.inventory.common.error.NotFoundException;
import com.sample.inventory.inventory.Inventory;
import com.sample.inventory.inventory.InventoryRepository;
import com.sample.inventory.movement.MovementType;
import com.sample.inventory.movement.MovementWriter;
import com.sample.inventory.product.Product;
import com.sample.inventory.product.ProductRepository;
import com.sample.inventory.warehouse.WarehouseRepository;
import java.util.Comparator;
import java.util.HashSet;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PurchaseService {

  private final PurchaseOrderRepository poRepo;
  private final ProductRepository productRepo;
  private final InventoryRepository invRepo;
  private final WarehouseRepository warehouseRepo;
  private final MovementWriter movements;

  @Transactional
  public PurchaseResponse create(CreatePurchaseRequest req) {
    var seen = new HashSet<Long>();
    for (var l : req.lines()) {
      if (!seen.add(l.productId())) {
        throw new DomainException(ErrorCode.VALIDATION);
      }
    }
    var po = new PurchaseOrder();
    for (var l : req.lines()) {
      Product product =
          productRepo
              .findById(l.productId())
              .orElseThrow(() -> new NotFoundException("product", l.productId()));
      po.addLine(new PurchaseOrderLine(po, product, l.orderedQty()));
    }
    return PurchaseMapper.toResponse(poRepo.save(po));
  }

  public PurchaseResponse get(long id) {
    return poRepo
        .findById(id)
        .map(PurchaseMapper::toResponse)
        .orElseThrow(() -> new NotFoundException("purchase order", id));
  }

  public Page<PurchaseResponse> list(Pageable pageable) {
    return poRepo.findAll(pageable).map(PurchaseMapper::toResponse);
  }

  @Transactional
  public PurchaseResponse receive(long id, ReceiveRequest req) {
    var po = poRepo.lockById(id).orElseThrow(() -> new NotFoundException("purchase order", id));
    if (po.getStatus() != PurchaseOrderStatus.OPEN) {
      throw new DomainException(ErrorCode.VALIDATION);
    }
    var sorted =
        req.items().stream()
            .sorted(
                Comparator.comparing(ReceiveRequest.ReceiveItem::productId)
                    .thenComparing(ReceiveRequest.ReceiveItem::warehouseId))
            .toList();
    for (var item : sorted) {
      var line =
          po.getLines().stream()
              .filter(l -> l.getProduct().getId().equals(item.productId()))
              .findFirst()
              .orElseThrow(() -> new NotFoundException("purchase line", item.productId()));
      var inv =
          invRepo
              .lockOne(item.productId(), item.warehouseId())
              .orElseGet(
                  () -> {
                    var wh =
                        warehouseRepo
                            .findById(item.warehouseId())
                            .orElseThrow(
                                () -> new NotFoundException("warehouse", item.warehouseId()));
                    return invRepo.save(new Inventory(line.getProduct(), wh, 0, 0, 0));
                  });
      line.receive(item.qty());
      inv.add(item.qty());
      movements.write(
          line.getProduct(),
          inv.getWarehouse(),
          MovementType.IN,
          item.qty(),
          "PURCHASE",
          po.getId());
    }
    po.completeIfFulfilled();
    return PurchaseMapper.toResponse(po);
  }
}
