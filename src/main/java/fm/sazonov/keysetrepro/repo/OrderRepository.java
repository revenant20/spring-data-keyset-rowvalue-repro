package fm.sazonov.keysetrepro.repo;

import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Window;
import org.springframework.data.jpa.repository.JpaRepository;
import fm.sazonov.keysetrepro.domain.Order;
import fm.sazonov.keysetrepro.domain.OrderStatus;

/**
 * Spring Data derived query for keyset scrolling.
 *
 * findFirst20ByStatus is processed by JpaKeysetScrollQueryCreator → KeysetScrollDelegate,
 * which always emits an OR-form predicate. See:
 *   https://github.com/spring-projects/spring-data-jpa/blob/4.0.5/spring-data-jpa/src/main/java/org/springframework/data/jpa/repository/query/KeysetScrollDelegate.java#L76
 *
 * The custom fragment {@link OrderRepositoryCustom} provides an alternative path that
 * emits row-value tuple comparison for comparison.
 */
public interface OrderRepository extends JpaRepository<Order, Long>, OrderRepositoryCustom {

    Window<Order> findFirst20ByStatus(OrderStatus status, ScrollPosition position, Sort sort);
}
