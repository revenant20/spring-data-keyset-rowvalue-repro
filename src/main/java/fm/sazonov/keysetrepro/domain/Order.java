package fm.sazonov.keysetrepro.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Minimal entity. Composite index (status, created_at, id) — equality first, sort columns last.
 * Tie-breaker by id makes the keyset stable when created_at has duplicates.
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    private Long id;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrderStatus status;

    protected Order() {
    }

    public Order(Long id, Instant createdAt, OrderStatus status) {
        this.id = id;
        this.createdAt = createdAt;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public OrderStatus getStatus() {
        return status;
    }
}
