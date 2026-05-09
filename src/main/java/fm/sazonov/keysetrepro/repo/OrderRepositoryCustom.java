package fm.sazonov.keysetrepro.repo;

import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import fm.sazonov.keysetrepro.domain.Order;
import fm.sazonov.keysetrepro.domain.OrderStatus;

/**
 * Custom repository fragment to bypass two limitations:
 *
 * 1. Spring Data JPA forbids returning Window&lt;T&gt; from {@code @Query}-annotated methods:
 *    https://github.com/spring-projects/spring-data-jpa/blob/4.0.5/spring-data-jpa/src/main/java/org/springframework/data/jpa/repository/query/JpaQueryLookupStrategy.java#L223
 *
 * 2. Derived-query keyset path goes through KeysetScrollDelegate.createPredicate, which
 *    algorithmically builds a disjunctive normal form via the single-column QueryStrategy:
 *    https://github.com/spring-projects/spring-data-jpa/blob/4.0.5/spring-data-jpa/src/main/java/org/springframework/data/jpa/repository/query/KeysetScrollSpecification.java#L155
 *
 * The fragment uses {@link jakarta.persistence.EntityManager} directly with HQL tuple
 * comparison and assembles {@code Window&lt;T&gt;} via {@code Window.from(...)}.
 */
public interface OrderRepositoryCustom {

    Window<Order> scrollHqlRowValue(OrderStatus status, ScrollPosition position, int size);
}
