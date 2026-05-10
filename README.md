# spring-data-keyset-rowvalue-repro

Minimal reproduction for [spring-projects/spring-data-jpa#4250](https://github.com/spring-projects/spring-data-jpa/issues/4250):
keyset queries via `Window<T>` / `KeysetScrollPosition` always emit a plain
disjunctive-normal-form predicate that Postgres cannot fold into the index.
Three alternative shapes — all algebraically equivalent — produce the
same result with constant per-page time.

## TL;DR

Three SQL shapes for the same keyset condition. All produce identical results;
the difference is how Postgres lowers them against a composite
`(status, created_at, id)` index.

| Shape | SQL emitted | Postgres plan | Time on 50k rows, page 30k |
|---|---|---|---|
| **OR plain** (what Spring emits) | `WHERE status=? AND (a < ? OR (a = ? AND b < ?))` | `Index Cond: status` + **Filter** (rejects 30k rows) | **~1.3 ms** |
| **OR smart** (algebraic rewrite) | `WHERE status=? AND a <= ? AND (a < ? OR b < ?)` | `Index Cond: status AND a <= ?` + tiny Filter | **~0.02 ms** |
| **Row-value** (tuple comparison) | `WHERE status=? AND (a, b) < (?, ?)` | `Index Cond: status AND ROW(a,b) < ROW(?,?)` | **~0.02 ms** |

`OR smart` and `row-value` are **identical in performance**. The smart-OR rewrite
works on **every dialect** because it uses only standard `<=`, `<`, `=`, `AND`, `OR` —
no row-value tuple constructor required.

## Why `OR plain` cannot be optimized by Postgres

The `OR` is at the top of the predicate. Postgres folds neither branch into the
composite index — both are kept in `Filter` and applied to the entire
`status='PAID'` subset returned by `Index Cond`. As pagination depth grows, the
Filter rejects progressively more rows.

`OR smart` lifts a range predicate (`created_at <= ?`) up to the AND level. Postgres
recognizes this as part of the composite Index Cond — the scan starts directly at
the cursor position and reads only LIMIT rows. The remaining inner OR runs on a
narrow range.

`row-value` does the same thing through tuple syntax. It requires the dialect to
support `(a, b) < (?, ?)` GtLt comparison
([Hibernate dialect gate](https://github.com/hibernate/hibernate-orm/blob/7.2.12/hibernate-core/src/main/java/org/hibernate/dialect/Dialect.java#L6434)),
which Oracle / SQL Server / DB2 / Sybase / HANA / Spanner / HSQLDB do **not**.
Smart-OR has no such constraint.

## Walking the dataset shows the asymptotics

`KeysetSqlShapeTest.scaling_orFormGrowsLinearly_rowValueIsConstant` walks the entire
35k-PAID subset page by page (pageSize = 200 → 175 pages), interleaving all three
shapes. Postgres-side `Execution Time` and `Buffers` from `EXPLAIN (ANALYZE, BUFFERS)`:

```
page    OR plain ms  OR plain buf   OR smart ms  OR smart buf    RV ms  RV buf
1            0.057        5            0.051        5            0.048    5
50           0.495       92            0.051        6            0.048    6
100          0.900      180            0.043        5            0.045    5
150          1.480      269            0.045        6            0.061    6
170          1.648      304            0.049        5            0.043    5
```

OR plain grows ~16× across 170 pages (linearly with depth). OR smart and row-value
stay flat at 5-6 buffers throughout.

## Run

Requires Java 25, Maven, and Docker (Testcontainers needs it).

```bash
mvn test
```

Four tests in `KeysetSqlShapeTest`:

- `springDerivedQuery_emitsOrFormSql` — captures the SQL Spring Data emits for
  `findFirst20ByStatus(...)` and asserts it is **plain OR-form** (specifically
  contains `created_at = ?` equality branch, does NOT contain `created_at <= ?`
  smart-OR signature, does NOT contain row-value tuple).
- `customFragment_emitsRowValueSql` — asserts the custom fragment emits a row-value
  tuple for comparison.
- `benchmark_orForm_vs_rowValue` — single deep page (OFFSET 30k), 3 warmup + 10
  measured iterations, prints per-iteration timings, summary medians, and a sample
  EXPLAIN plan for each shape.
- `scaling_orFormGrowsLinearly_rowValueIsConstant` — full pagination walk through
  all 35k PAID rows, reports per-page Execution Time and Buffers for all three
  shapes plus head-vs-tail growth ratios.

The seed is 50 000 rows by default. Bumping it shows even more dramatic numbers.

## Manual EXPLAIN with a Postgres shell

```bash
docker compose -f compose.yaml up -d
docker exec -it $(docker compose ps -q postgres) psql -U keyset -d keyset
```

The three SQL queries are reproduced verbatim in the test sources.

## Stack

- Java 25
- Spring Boot 4.0.6 (Spring Data JPA 4.0.5, Hibernate 7.2.12)
- Postgres 17 via Testcontainers + `@ServiceConnection`
- Liquibase

## See also

- Issue: [spring-projects/spring-data-jpa#4250](https://github.com/spring-projects/spring-data-jpa/issues/4250)
- [`KeysetScrollDelegate.java#L76`](https://github.com/spring-projects/spring-data-jpa/blob/4.0.5/spring-data-jpa/src/main/java/org/springframework/data/jpa/repository/query/KeysetScrollDelegate.java#L76) — where Spring Data builds the OR-form. The smart-OR rewrite would also live here.
- [`KeysetScrollSpecification.java#L155`](https://github.com/spring-projects/spring-data-jpa/blob/4.0.5/spring-data-jpa/src/main/java/org/springframework/data/jpa/repository/query/KeysetScrollSpecification.java#L155) — single-column compare strategy.
- [`JpaQueryLookupStrategy.java#L223`](https://github.com/spring-projects/spring-data-jpa/blob/4.0.5/spring-data-jpa/src/main/java/org/springframework/data/jpa/repository/query/JpaQueryLookupStrategy.java#L223) — `throw` blocking `Window<T>` from `@Query`.
- [Hibernate `Dialect.java#L6434`](https://github.com/hibernate/hibernate-orm/blob/7.2.12/hibernate-core/src/main/java/org/hibernate/dialect/Dialect.java#L6434) — `supportsRowValueConstructorGtLtSyntax` is the dialect gate for row-value (irrelevant for smart-OR).
