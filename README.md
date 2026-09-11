# Inventory & Order Management System

Backend portfolio (Java Developer): manage **products & inventory across multiple warehouses**,
keep stock accurate under **concurrent orders**. Built with **Java + Spring Boot + PostgreSQL + Redis + Kafka + Docker**.

> GitHub: https://github.com/dhaanisaputra/inventory-order-management-system

## Key Features (roadmap)

- [ ] Product management
- [ ] Multiple warehouses
- [ ] Real-time inventory
- [ ] Concurrency-safe ordering (transactions + locking)
- [ ] Order management
- [ ] Stock reservation
- [ ] Returns
- [ ] Purchase orders
- [ ] Low-stock alerts
- [ ] Inventory synchronization
- [ ] Stock movement history

## You'll see in this repo

Database modeling, transactions, concurrency, pessimistic/optimistic locking,
event-driven architecture (Kafka), caching (Redis), distributed backend concepts.

## AI Features (nice to have, later)

- [ ] Demand forecasting
- [ ] Smart stock replenishment
- [ ] Inventory anomaly detection
- [ ] Product recommendations
- [ ] AI inventory assistant

## Quickstart

```bash
docker compose up -d
./mvnw spring-boot:run
```

Default: app `:8080`, Postgres `:5432` (inventory/inventory, db `inventory_db`),
Redis `:6379`, Kafka `:9092`. Health: `/actuator/health`.

## Project structure (planned)

```
src/main/java/com/dhaanisaputra/inventory/
  product/ warehouse/ inventory/ order/ reservation/
  purchase/ returns/ alert/ movement/ common/
```

MVP order: product + warehouse + inventory + order with reservation → movement history →
purchase/returns/alerts → Kafka + Redis → AI stubs.
