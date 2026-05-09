package fm.sazonov.keysetrepro;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Window;
import org.springframework.jdbc.core.JdbcTemplate;
import fm.sazonov.keysetrepro.domain.Order;
import fm.sazonov.keysetrepro.domain.OrderStatus;
import fm.sazonov.keysetrepro.repo.OrderRepository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the SQL shape that Spring Data emits for a Window&lt;T&gt; keyset query
 * versus a custom repository fragment using HQL tuple comparison. Captures the
 * SQL via {@link CapturedOutput} (org.hibernate.SQL is at DEBUG in test config).
 *
 * Also runs EXPLAIN (ANALYZE, BUFFERS) on both shapes against the same composite
 * index and prints the plans to stdout. With ~50_000 rows the difference between
 * Index Cond + Filter (OR-form) and pure Index Cond (row-value) is observable;
 * with millions of rows it becomes 2-3 orders of magnitude.
 */
@ExtendWith(OutputCaptureExtension.class)
class KeysetSqlShapeTest extends AbstractPostgresTest {

    private static final int ROWS = 50_000;
    private static final Sort SORT = Sort.by(Sort.Direction.DESC, "createdAt", "id");
    private static final Sort.Direction DESC = Sort.Direction.DESC;

    @Autowired
    private OrderRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM orders", Long.class);
        if (count != null && count >= ROWS) {
            return;
        }
        jdbc.execute("TRUNCATE TABLE orders");

        Instant base = Instant.now().minus(365, ChronoUnit.DAYS);
        List<Object[]> batch = new ArrayList<>(5_000);
        for (long id = 1; id <= ROWS; id++) {
            int slot = (int) (id % 10);
            String status = slot < 7 ? "PAID" : slot < 9 ? "PENDING" : "CANCELLED";
            batch.add(new Object[]{
                    id,
                    Timestamp.from(base.plusSeconds(id)),
                    status
            });
            if (batch.size() == 5_000) {
                jdbc.batchUpdate(
                        "INSERT INTO orders (id, created_at, status) VALUES (?, ?, ?)",
                        batch);
                batch.clear();
            }
        }
        jdbc.execute("ANALYZE orders");
    }

    @Test
    void springDerivedQuery_emitsOrFormSql(CapturedOutput output) {
        // Build a non-initial KeysetScrollPosition so the predicate appears in SQL.
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put("createdAt", Instant.now());
        keys.put("id", 100L);
        ScrollPosition position = ScrollPosition.forward(keys);

        Window<Order> window = repository.findFirst20ByStatus(OrderStatus.PAID, position, SORT);
        // Force materialization so the SQL is logged.
        window.getContent().size();

        String sql = lastHibernateSql(output);
        System.out.println();
        System.out.println("[Spring derived query — Window<T>] SQL:");
        System.out.println("  " + sql);

        assertThat(sql)
                .as("Spring Data derived query is expected to emit OR-form keyset predicate")
                .containsIgnoringCase(" or ")
                .doesNotContainIgnoringCase("(o1_0.created_at,o1_0.id)<");
    }

    @Test
    void customFragment_emitsRowValueSql(CapturedOutput output) {
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put("createdAt", Instant.now());
        keys.put("id", 100L);
        ScrollPosition position = ScrollPosition.forward(keys);

        Window<Order> window = repository.scrollHqlRowValue(OrderStatus.PAID, position, 20);
        window.getContent().size();

        String sql = lastHibernateSql(output);
        System.out.println();
        System.out.println("[Custom fragment — HQL row-value] SQL:");
        System.out.println("  " + sql);

        assertThat(sql)
                .as("Custom fragment with HQL tuple comparison is expected to emit row-value SQL")
                .containsIgnoringCase("(o1_0.created_at,o1_0.id)<")
                .doesNotContainIgnoringCase("o1_0.created_at<? or o1_0.created_at=?");
    }

    @Test
    void explainAnalyze_orForm_vs_rowValue() {
        Map<String, Object> cursor = jdbc.queryForMap("""
                SELECT created_at, id FROM orders
                WHERE status = 'PAID'
                ORDER BY created_at DESC, id DESC
                OFFSET 30000 LIMIT 1
                """);
        Timestamp ts = (Timestamp) cursor.get("created_at");
        long id = ((Number) cursor.get("id")).longValue();

        System.out.println();
        System.out.println("=== EXPLAIN (ANALYZE, BUFFERS): OR-form (what Spring emits) ===");
        explain("""
                SELECT id, created_at, status FROM orders
                WHERE status = 'PAID'
                  AND (created_at < ? OR (created_at = ? AND id < ?))
                ORDER BY created_at DESC, id DESC
                LIMIT 5
                """, ts, ts, id);

        System.out.println();
        System.out.println("=== EXPLAIN (ANALYZE, BUFFERS): row-value (what custom fragment emits) ===");
        explain("""
                SELECT id, created_at, status FROM orders
                WHERE status = 'PAID'
                  AND (created_at, id) < (?, ?)
                ORDER BY created_at DESC, id DESC
                LIMIT 5
                """, ts, id);
    }

    private void explain(String sql, Object... params) {
        List<String> plan = jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) " + sql, String.class, params);
        plan.forEach(line -> System.out.println("  " + line));
    }

    /**
     * Returns the most recent SQL line emitted by org.hibernate.SQL during the test.
     */
    private static String lastHibernateSql(CapturedOutput output) {
        String[] lines = output.getOut().split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            int idx = lines[i].indexOf("org.hibernate.SQL");
            if (idx >= 0) {
                int colon = lines[i].indexOf(':', idx);
                return colon > 0 ? lines[i].substring(colon + 1).trim() : lines[i];
            }
        }
        throw new IllegalStateException("No org.hibernate.SQL line found in captured output");
    }
}
