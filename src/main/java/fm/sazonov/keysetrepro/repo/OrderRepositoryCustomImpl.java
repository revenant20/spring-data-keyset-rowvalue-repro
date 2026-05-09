package fm.sazonov.keysetrepro.repo;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import fm.sazonov.keysetrepro.domain.Order;
import fm.sazonov.keysetrepro.domain.OrderStatus;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * Implementation of the custom fragment. Class name suffix {@code Impl} matches Spring Data's
 * fragment-resolution convention (see {@link OrderRepository}).
 */
public class OrderRepositoryCustomImpl implements OrderRepositoryCustom {

    private static final String HQL_FIRST_PAGE = """
            SELECT o FROM Order o
            WHERE o.status = :status
            ORDER BY o.createdAt DESC, o.id DESC
            """;

    /**
     * Tuple comparison in HQL. Hibernate 7 parses {@code (a, b) < (?, ?)} via SqmTuple
     * (HqlParser.g4 TupleExpression + BinaryExpressionPredicate) and emits SQL
     * {@code (created_at, id) < (?, ?)} on Postgres / MySQL / H2 / CockroachDB / MariaDB,
     * yielding an Index Cond on the composite (status, created_at, id) index instead of
     * a Filter scan.
     *
     * Hibernate gates this per dialect via {@code Dialect.supportsRowValueConstructorGtLtSyntax}:
     *   https://github.com/hibernate/hibernate-orm/blob/7.2.12/hibernate-core/src/main/java/org/hibernate/dialect/Dialect.java#L6434
     *
     * Dialects that override to return false (Oracle, SQL Server, DB2, Sybase, HANA, Spanner,
     * HSQLDB) still need OR-form. This reproduction targets Postgres, where Hibernate emits
     * the tuple verbatim.
     */
    private static final String HQL_NEXT_PAGE = """
            SELECT o FROM Order o
            WHERE o.status = :status
              AND (o.createdAt, o.id) < (:ts, :id)
            ORDER BY o.createdAt DESC, o.id DESC
            """;

    @PersistenceContext
    private EntityManager em;

    @Override
    public Window<Order> scrollHqlRowValue(OrderStatus status, ScrollPosition position, int size) {

        if (!(position instanceof KeysetScrollPosition keyset)) {
            throw new IllegalArgumentException("Only KeysetScrollPosition is supported, got " + position);
        }

        Map<String, Object> keys = keyset.getKeys();
        boolean initial = keys.isEmpty();

        TypedQuery<Order> query = em.createQuery(initial ? HQL_FIRST_PAGE : HQL_NEXT_PAGE, Order.class);
        query.setParameter("status", status);
        if (!initial) {
            query.setParameter("ts", (Instant) keys.get("createdAt"));
            query.setParameter("id", (Long) keys.get("id"));
        }
        // size+1 to detect hasNext without a separate COUNT query — same trick Spring uses
        // internally in ScrollDelegate.scroll.
        query.setMaxResults(size + 1);

        List<Order> raw = query.getResultList();
        boolean hasNext = raw.size() > size;
        List<Order> items = hasNext ? raw.subList(0, size) : raw;

        IntFunction<ScrollPosition> positionFunction = index -> {
            Order o = items.get(index);
            Map<String, Object> nextKeys = new LinkedHashMap<>();
            nextKeys.put("createdAt", o.getCreatedAt());
            nextKeys.put("id", o.getId());
            return ScrollPosition.forward(nextKeys);
        };

        return Window.from(items, positionFunction, hasNext);
    }
}
