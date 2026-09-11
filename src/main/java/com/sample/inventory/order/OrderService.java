package com.sample.inventory.order;

import com.sample.inventory.common.error.IdempotencyConflictException;
import com.sample.inventory.common.error.InsufficientStockException;
import com.sample.inventory.common.error.NotFoundException;
import com.sample.inventory.events.KafkaTopics;
import com.sample.inventory.events.LowStockNotifier;
import com.sample.inventory.events.OutboxWriter;
import com.sample.inventory.inventory.InventoryRepository;
import com.sample.inventory.movement.MovementType;
import com.sample.inventory.movement.MovementWriter;
import com.sample.inventory.product.Product;
import com.sample.inventory.product.ProductRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

  private final SalesOrderRepository orderRepo;
  private final OrderIdempotencyRepository idemRepo;
  private final ReservationRepository reservationRepo;
  private final InventoryRepository invRepo;
  private final ProductRepository productRepo;
  private final MovementWriter movements;
  private final OutboxWriter outbox;
  private final LowStockNotifier notifier;
  private final ReservationProperties props;
  private final Clock clock;

  @Transactional
  @CacheEvict(value = "inv", allEntries = true)
  public OrderResponse create(CreateOrderRequest req, String idemKey, String reqHash) {
    if (idemKey != null) {
      var existing = idemRepo.findById(idemKey);
      if (existing.isPresent()) {
        if (!Objects.equals(existing.get().getRequestHash(), reqHash)) {
          throw new IdempotencyConflictException();
        }
        return get(existing.get().getOrder().getId());
      }
    }
    var order = new SalesOrder();
    var sorted =
        req.lines().stream()
            .sorted(Comparator.comparing(CreateOrderRequest.CreateOrderLine::productId))
            .toList();
    record Pending(Allocation allocation, Reservation reservation) {}
    var pending = new ArrayList<Pending>();
    for (var line : sorted) {
      Product product =
          productRepo
              .findById(line.productId())
              .orElseThrow(() -> new NotFoundException("product", line.productId()));
      var ol = new OrderLine(order, product, line.qty());
      order.addLine(ol);
      int rest = line.qty();
      for (var inv : invRepo.lockAvailable(product.getId())) {
        if (rest == 0) {
          break;
        }
        int take = Math.min(rest, inv.getAvailable());
        inv.reserve(take);
        var allocation = new Allocation(ol, inv.getWarehouse(), take);
        ol.addAllocation(allocation);
        pending.add(
            new Pending(
                allocation, new Reservation(allocation, take, clock.instant().plus(props.ttl()))));
        rest -= take;
      }
      if (rest > 0) {
        throw new InsufficientStockException(product.getSku());
      }
    }
    orderRepo.saveAndFlush(order);
    for (var p : pending) {
      reservationRepo.save(p.reservation());
      p.allocation().linkReservation(p.reservation());
      movements.write(
          p.allocation().getOrderLine().getProduct(),
          p.allocation().getWarehouse(),
          MovementType.RESERVE,
          p.allocation().getQty(),
          "ORDER",
          order.getId());
    }
    if (idemKey != null) {
      try {
        idemRepo.saveAndFlush(new OrderIdempotency(idemKey, reqHash, order));
      } catch (DataIntegrityViolationException e) {
        var existing = idemRepo.findById(idemKey).orElseThrow(() -> e);
        if (!Objects.equals(existing.getRequestHash(), reqHash)) {
          throw new IdempotencyConflictException();
        }
        return get(existing.getOrder().getId());
      }
    }
    outbox.write(KafkaTopics.ORDER_CREATED, String.valueOf(order.getId()),
        Map.of("orderId", order.getId(), "status", order.getStatus().name()));
    return get(order.getId());
  }

  public OrderResponse get(long id) {
    return orderRepo
        .findDetailedById(id)
        .map(OrderMapper::toResponse)
        .orElseThrow(() -> new NotFoundException("order", id));
  }

  public Page<OrderResponse> search(
      OrderStatus status, Instant from, Instant to, Pageable pageable) {
    Instant f = from != null ? from : Instant.EPOCH;
    Instant t = to != null ? to : clock.instant();
    return orderRepo.search(status, f, t, pageable).map(OrderMapper::toResponse);
  }

  @Transactional
  @CacheEvict(value = "inv", allEntries = true)
  public OrderResponse confirm(long id) {
    var order =
        orderRepo.findDetailedById(id).orElseThrow(() -> new NotFoundException("order", id));
    if (order.getStatus() == OrderStatus.CONFIRMED) {
      return OrderMapper.toResponse(order);
    }
    order.confirm();
    outbox.write(KafkaTopics.ORDER_CONFIRMED, String.valueOf(order.getId()),
        Map.of("orderId", order.getId(), "status", order.getStatus().name()));
    Map<Long, Reservation> resByAlloc = new HashMap<>();
    for (var r : reservationRepo.lockByOrderId(id)) {
      resByAlloc.put(r.getAllocation().getId(), r);
    }
    var sortedLines =
        order.getLines().stream()
            .sorted(Comparator.comparing(l -> l.getProduct().getId()))
            .toList();
    for (var line : sortedLines) {
      var sortedAllocs =
          line.getAllocations().stream()
              .sorted(Comparator.comparing(a -> a.getWarehouse().getId()))
              .toList();
      for (var alloc : sortedAllocs) {
        var inv =
            invRepo
                .lockOne(line.getProduct().getId(), alloc.getWarehouse().getId())
                .orElseThrow(
                    () ->
                        new NotFoundException(
                            "inventory",
                            line.getProduct().getId() + "/" + alloc.getWarehouse().getId()));
        inv.confirm(alloc.getQty());
        notifier.notifyIfLow(inv);
        resByAlloc.get(alloc.getId()).confirm();
        movements.write(
            line.getProduct(),
            alloc.getWarehouse(),
            MovementType.OUT,
            alloc.getQty(),
            "ORDER",
            order.getId());
      }
    }
    return OrderMapper.toResponse(order);
  }

  @Transactional
  @CacheEvict(value = "inv", allEntries = true)
  public OrderResponse cancel(long id) {
    var order =
        orderRepo.findDetailedById(id).orElseThrow(() -> new NotFoundException("order", id));
    order.cancel();
    outbox.write(KafkaTopics.ORDER_CANCELLED, String.valueOf(order.getId()),
        Map.of("orderId", order.getId(), "status", order.getStatus().name()));
    Map<Long, Reservation> resByAlloc = new HashMap<>();
    for (var r : reservationRepo.lockByOrderId(id)) {
      resByAlloc.put(r.getAllocation().getId(), r);
    }
    var sortedLines =
        order.getLines().stream()
            .sorted(Comparator.comparing(l -> l.getProduct().getId()))
            .toList();
    for (var line : sortedLines) {
      var sortedAllocs =
          line.getAllocations().stream()
              .sorted(Comparator.comparing(a -> a.getWarehouse().getId()))
              .toList();
      for (var alloc : sortedAllocs) {
        var inv =
            invRepo
                .lockOne(line.getProduct().getId(), alloc.getWarehouse().getId())
                .orElseThrow(
                    () ->
                        new NotFoundException(
                            "inventory",
                            line.getProduct().getId() + "/" + alloc.getWarehouse().getId()));
        inv.release(alloc.getQty());
        resByAlloc.get(alloc.getId()).cancel();
        movements.write(
            line.getProduct(),
            alloc.getWarehouse(),
            MovementType.RELEASE,
            alloc.getQty(),
            "ORDER",
            order.getId());
      }
    }
    return OrderMapper.toResponse(order);
  }
}
