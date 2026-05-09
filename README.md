# spring-data-keyset-rowvalue-repro

Minimal reproduction for a Spring Data JPA issue: keyset queries via `Window<T>` /
`KeysetScrollPosition` always emit OR-form predicate, even on dialects that natively
support row-value tuple comparison (Postgres / MySQL / H2 / CockroachDB / MariaDB).

## TL;DR

Two Spring Data repository methods, identical sort, identical seed. The first uses
the standard derived-query path. The second uses a custom repository fragment with
HQL tuple comparison.

| Method | SQL emitted | Postgres plan | Time on 1M rows |
|---|---|---|---|
| `findFirst20ByStatus(status, ScrollPosition, Sort)` (Spring derived) | `WHERE a < ? OR (a = ? AND b < ?)` | `Index Cond: status` + **Filter** | ~700 ms |
| `scrollHqlRowValue(status, ScrollPosition, size)` (custom fragment) | `WHERE (a, b) < (?, ?)` | **Index Cond: `status=? AND ROW(a,b) < ROW(?,?)`** | ~0.3 ms |

Same result, same `Window<T>` API, ~2000× difference at depth.

## Run

Requires Java 25, Maven, and Docker (Testcontainers needs it).

```bash
mvn test
```

This runs three tests in `KeysetSqlShapeTest`:

- `springDerivedQuery_emitsOrFormSql` — calls
  `repository.findFirst20ByStatus(...)` and asserts the captured Hibernate SQL
  contains an OR-form predicate.
- `customFragment_emitsRowValueSql` — calls
  `repository.scrollHqlRowValue(...)` and asserts the SQL contains a row-value
  tuple comparison.
- `explainAnalyze_orForm_vs_rowValue` — runs `EXPLAIN (ANALYZE, BUFFERS)` against
  Postgres for both shapes and prints the plans.

The seed is 50 000 rows. Increase `ROWS` constant in
`KeysetSqlShapeTest` to make the EXPLAIN difference more dramatic (1M rows shows
two orders of magnitude in time and three in buffers).

## Manual EXPLAIN with full data

```bash
docker compose -f compose.yaml up -d
# Then run mvn spring-boot:run with seeding code, or insert rows manually.
docker exec -it $(docker compose ps -q postgres) psql -U keyset -d keyset
```

The two SQL queries to compare are reproduced verbatim in
`KeysetSqlShapeTest.explainAnalyze_orForm_vs_rowValue`.

## Stack

- Java 25
- Spring Boot 4.0.6 (Spring Data JPA 4.0.5, Hibernate 7.2.12)
- Postgres 17 via Testcontainers + `@ServiceConnection`
- Liquibase

## See also

- [`KeysetScrollDelegate.java#L76`](https://github.com/spring-projects/spring-data-jpa/blob/4.0.5/spring-data-jpa/src/main/java/org/springframework/data/jpa/repository/query/KeysetScrollDelegate.java#L76) — where Spring Data builds the OR-form.
- [`KeysetScrollSpecification.java#L155`](https://github.com/spring-projects/spring-data-jpa/blob/4.0.5/spring-data-jpa/src/main/java/org/springframework/data/jpa/repository/query/KeysetScrollSpecification.java#L155) — single-column compare strategy.
- [`JpaQueryLookupStrategy.java#L223`](https://github.com/spring-projects/spring-data-jpa/blob/4.0.5/spring-data-jpa/src/main/java/org/springframework/data/jpa/repository/query/JpaQueryLookupStrategy.java#L223) — `throw` blocking `Window<T>` from `@Query`.
- [Hibernate `Dialect.java#L6434`](https://github.com/hibernate/hibernate-orm/blob/7.2.12/hibernate-core/src/main/java/org/hibernate/dialect/Dialect.java#L6434) — `supportsRowValueConstructorGtLtSyntax` is the dialect gate.
