package com.sample.inventory.returns;

import com.sample.inventory.common.error.DomainException;
import com.sample.inventory.common.error.ErrorCode;
import com.sample.inventory.common.error.NotFoundException;
import com.sample.inventory.common.error.ReturnExceededException;
import com.sample.inventory.inventory.InventoryRepository;
import com.sample.inventory.movement.MovementType;
import com.sample.inventory.movement.MovementWriter;
import com.sample.inventory.order.Allocation;
import com.sample.inventory.order.AllocationRepository;
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
public class ReturnService {

  private final ReturnOrderRepository returnRepo;
  private final ReturnLineRepository lineRepo;
  private final AllocationRepository allocationRepo;
  private final InventoryRepository invRepo;
  private final MovementWriter movements;

  @Transactional
  public ReturnResponse create(CreateReturnRequest req) {
    var seen = new HashSet<Long>();
    for (var l : req.lines()) {
      if (!seen.add(l.allocationId())) {
        throw new DomainException(ErrorCode.VALIDATION);
      }
    }
    var order = new ReturnOrder();
    var sorted = req.lines().stream()
        .sorted(Comparator.comparing(CreateReturnRequest.CreateReturnLine::allocationId))
        .toList();
    for (var line : sorted) {
      Allocation alloc = allocationRepo.findById(line.allocationId())
          .orElseThrow(() -> new NotFoundException("allocation", line.allocationId()));
      int already = lineRepo.sumReturnedByAllocation(alloc.getId());
      if (line.qty() + already > alloc.getQty()) {
        throw new ReturnExceededException(alloc.getId());
      }
      var inv = invRepo.lockOne(
              alloc.getOrderLine().getProduct().getId(), alloc.getWarehouse().getId())
          .orElseThrow(() -> new NotFoundException("inventory",
              alloc.getOrderLine().getProduct().getId() + "/" + alloc.getWarehouse().getId()));
      inv.add(line.qty());
      order.addLine(new ReturnLine(order, alloc, line.qty()));
    }
    returnRepo.saveAndFlush(order);
    for (var l : order.getLines()) {
      movements.write(l.getAllocation().getOrderLine().getProduct(),
          l.getAllocation().getWarehouse(), MovementType.IN, l.getQty(), "RETURN", order.getId());
    }
    return ReturnMapper.toResponse(order);
  }

  public ReturnResponse get(long id) {
    return returnRepo.findById(id).map(ReturnMapper::toResponse)
        .orElseThrow(() -> new NotFoundException("return", id));
  }

  public Page<ReturnResponse> list(Pageable pageable) {
    return returnRepo.findAll(pageable).map(ReturnMapper::toResponse);
  }
}
