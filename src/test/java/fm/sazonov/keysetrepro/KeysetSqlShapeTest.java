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
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

        // Verify Spring emits the slow plain OR-form, not smart-OR or row-value.
        //
        //   plain OR  : a < ? OR (a = ? AND b < ?)            ← our claim
        //   smart OR  : a <= ? AND (a < ? OR b < ?)           ← would be fast on Postgres
        //   row-value : (a, b) < (?, ?)                       ← also fast on Postgres
        //
        // Distinguishing markers:
        //   plain-OR shape contains "created_at=?" — equality compare on the leading sort key
        //   smart-OR shape contains "created_at<=?" — range compare promoted to AND level
        //   row-value contains "(...,...)<(...,...)"  — tuple compare
        String normalized = sql.replaceAll("\\s+", "");
        assertThat(normalized)
                .as("Spring Data derived query is expected to emit plain OR-form (a < ? OR (a = ? AND b < ?)). " +
                    "If this fails, Spring may have started emitting smart-OR or row-value — re-investigate. " +
                    "Actual SQL: " + sql)
                .contains("o1_0.created_at=?")        // marker of plain OR (equality branch)
                .contains("o1_0.id<?")                // tail comparison
                .doesNotContain("o1_0.created_at<=?") // smart-OR signature
                .doesNotContain("(o1_0.created_at,o1_0.id)<"); // row-value signature
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

    /**
     * Benchmark with warmup + N interleaved iterations.
     *
     * Time source: Postgres-side {@code Execution Time: X ms} from
     * {@code EXPLAIN (ANALYZE, BUFFERS)}. This is the server-measured execution time,
     * not JDBC wall-time, so it excludes network and result-set overhead. EXPLAIN ANALYZE
     * adds a small instrumentation overhead but it applies symmetrically to both shapes,
     * so the ratio is preserved.
     *
     * Iterations are interleaved (OR, RV, OR, RV, ...) so both shapes see the same
     * page-cache state — neither gets an unfair "cold" disadvantage.
     *
     * Buffers from the plan are deterministic after warmup and tell the structural story:
     * Filter scans the entire matching {@code status} range, Index Cond stops at LIMIT.
     */
    @Test
    void benchmark_orForm_vs_rowValue() {
        Map<String, Object> cursor = jdbc.queryForMap("""
                SELECT created_at, id FROM orders
                WHERE status = 'PAID'
                ORDER BY created_at DESC, id DESC
                OFFSET 30000 LIMIT 1
                """);
        Timestamp ts = (Timestamp) cursor.get("created_at");
        long id = ((Number) cursor.get("id")).longValue();

        String orFormSql = """
                SELECT id, created_at, status FROM orders
                WHERE status = 'PAID'
                  AND (created_at < ? OR (created_at = ? AND id < ?))
                ORDER BY created_at DESC, id DESC
                LIMIT 5
                """;
        String rowValueSql = """
                SELECT id, created_at, status FROM orders
                WHERE status = 'PAID'
                  AND (created_at, id) < (?, ?)
                ORDER BY created_at DESC, id DESC
                LIMIT 5
                """;

        // Warmup: prime JIT, JDBC connection, Postgres plan cache, page cache.
        for (int i = 0; i < 3; i++) {
            runExplain(orFormSql, ts, ts, id);
            runExplain(rowValueSql, ts, id);
        }

        // Measure: interleaved so both shapes see the same cache state.
        int n = 10;
        List<Double> orTimes = new ArrayList<>();
        List<Double> rvTimes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            orTimes.add(executionTimeMs(runExplain(orFormSql, ts, ts, id)));
            rvTimes.add(executionTimeMs(runExplain(rowValueSql, ts, id)));
        }

        // Per-iteration table.
        System.out.println();
        System.out.println("=== Per-iteration Execution Time (Postgres-side, ms) ===");
        System.out.printf(Locale.ROOT, "%-5s %12s %12s%n", "iter", "OR-form", "row-value");
        for (int i = 0; i < n; i++) {
            System.out.printf(Locale.ROOT, "%-5d %12.3f %12.3f%n", i + 1, orTimes.get(i), rvTimes.get(i));
        }

        double orMed = median(orTimes), rvMed = median(rvTimes);
        double orMin = orTimes.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        double orMax = orTimes.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
        double rvMin = rvTimes.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        double rvMax = rvTimes.stream().mapToDouble(Double::doubleValue).max().orElseThrow();

        System.out.println();
        System.out.printf(Locale.ROOT, "OR-form    median=%.3f ms  min=%.3f  max=%.3f%n", orMed, orMin, orMax);
        System.out.printf(Locale.ROOT, "row-value  median=%.3f ms  min=%.3f  max=%.3f%n", rvMed, rvMin, rvMax);
        System.out.printf(Locale.ROOT, "Ratio      OR/RV (medians) = %.1fx%n", orMed / rvMed);

        // Print one full plan per shape so the reader sees the structural difference
        // (Filter + Rows Removed by Filter vs Index Cond ROW(...) and the buffers).
        System.out.println();
        System.out.println("=== Sample plan: OR-form (what Spring Data emits) ===");
        runExplain(orFormSql, ts, ts, id).forEach(line -> System.out.println("  " + line));
        System.out.println();
        System.out.println("=== Sample plan: row-value (what the custom fragment emits) ===");
        runExplain(rowValueSql, ts, id).forEach(line -> System.out.println("  " + line));
    }

    /**
     * Walks three keyset shapes through the entire {@code status='PAID'} subset, page
     * by page, until queries return no more rows. Demonstrates the asymptotic
     * difference between:
     * <ul>
     *   <li><b>OR plain</b> ({@code a < ? OR (a = ? AND b < ?)}) — what Spring Data
     *       emits today. Grows linearly with page depth: Postgres puts the predicate
     *       in {@code Filter}, scans the whole {@code status='PAID'} subset, rejects
     *       progressively more rows.</li>
     *   <li><b>OR smart</b> ({@code a <= ? AND (a < ? OR b < ?)}) — algebraically
     *       identical, but the leading {@code a <= ?} is folded into {@code Index Cond}
     *       on the composite (status, created_at, id) index. Constant per page.
     *       Works on every dialect (no tuple needed).</li>
     *   <li><b>Row-value</b> ({@code (a, b) < (?, ?)}) — same plan as smart-OR.
     *       Requires dialect support for row-value GtLt.</li>
     * </ul>
     *
     * Pagination is interleaved (one page of each shape, repeat) so all three see
     * the same page-cache state at each step.
     *
     * <p>Measurement notes:
     * <ul>
     *   <li>Time source is Postgres-side {@code Execution Time} from
     *       {@code EXPLAIN (ANALYZE, BUFFERS)}, plus {@code Buffers: shared hit/read}
     *       totals. EXPLAIN ANALYZE actually executes the query; we re-run a regular
     *       SELECT only to obtain the cursor row for the next page (single extra
     *       roundtrip per iteration, not counted in the measurement).</li>
     *   <li>The 50k-row seed fits entirely in the default Postgres
     *       {@code shared_buffers} (128 MB), so disk I/O is not modeled. On
     *       production-sized data the OR-form would degrade further as the
     *       linearly-growing scan starts missing the cache; row-value is unaffected
     *       since it reads only LIMIT rows per page.</li>
     * </ul>
     */
    @Test
    void scaling_orFormGrowsLinearly_rowValueIsConstant() {
        int pageSize = 200;
        int warmupPages = 10;
        // Safety upper bound: prevent runaway in case the seeder ever produces
        // far more rows than expected. Walks normally terminate via exhausted=true
        // when both shapes return zero rows.
        int safetyCap = 1000;

        String firstSql = """
                SELECT id, created_at FROM orders
                WHERE status = 'PAID'
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """;
        String orPlainSql = """
                SELECT id, created_at FROM orders
                WHERE status = 'PAID'
                  AND (created_at < ? OR (created_at = ? AND id < ?))
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """;
        String orSmartSql = """
                SELECT id, created_at FROM orders
                WHERE status = 'PAID'
                  AND created_at <= ?
                  AND (created_at < ? OR id < ?)
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """;
        String rvNextSql = """
                SELECT id, created_at FROM orders
                WHERE status = 'PAID'
                  AND (created_at, id) < (?, ?)
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """;

        // Warmup: walk a few pages of each, interleaved (prime JIT + page cache).
        KeysetPosition warmOrPlain = new KeysetPosition();
        KeysetPosition warmOrSmart = new KeysetPosition();
        KeysetPosition warmRv = new KeysetPosition();
        for (int i = 0; i < warmupPages; i++) {
            measureAndAdvance(warmOrPlain, firstSql, orPlainSql, Shape.OR_PLAIN, pageSize);
            measureAndAdvance(warmOrSmart, firstSql, orSmartSql, Shape.OR_SMART, pageSize);
            measureAndAdvance(warmRv, firstSql, rvNextSql, Shape.ROW_VALUE, pageSize);
            if (warmOrPlain.exhausted || warmOrSmart.exhausted || warmRv.exhausted) break;
        }

        KeysetPosition orPlainPos = new KeysetPosition();
        KeysetPosition orSmartPos = new KeysetPosition();
        KeysetPosition rvPos = new KeysetPosition();
        List<Measurement> orPlainMs = new ArrayList<>();
        List<Measurement> orSmartMs = new ArrayList<>();
        List<Measurement> rvMs = new ArrayList<>();
        // Walk all three shapes through the dataset until exhaustion. They walk the
        // same status='PAID' rows in the same order and exhaust on the same iteration.
        for (int p = 0; p < safetyCap; p++) {
            Measurement orPlainM = measureAndAdvance(orPlainPos, firstSql, orPlainSql, Shape.OR_PLAIN, pageSize);
            Measurement orSmartM = measureAndAdvance(orSmartPos, firstSql, orSmartSql, Shape.OR_SMART, pageSize);
            Measurement rvM = measureAndAdvance(rvPos, firstSql, rvNextSql, Shape.ROW_VALUE, pageSize);
            if (orPlainPos.exhausted || orSmartPos.exhausted || rvPos.exhausted) {
                // Final iteration returned no rows — discard those degenerate
                // measurements and stop.
                break;
            }
            orPlainMs.add(orPlainM);
            orSmartMs.add(orSmartM);
            rvMs.add(rvM);
        }

        int actualPages = orPlainMs.size();
        assertThat(actualPages)
                .as("safetyCap reached without exhaustion — bump safetyCap or check seed size")
                .isLessThan(safetyCap);

        // Trim the last few pages from summary stats. Two effects can distort the
        // tail:
        //   (1) Partial pages — rows < pageSize, smaller scan depth, naturally faster.
        //   (2) Postgres prepared-statement plan caching may produce a "custom plan"
        //       for very deep cursors when the keyset predicate becomes extremely
        //       selective; the plan can push the predicate into Index Cond and skip
        //       the Filter entirely. Interesting but not representative of typical
        //       pagination depth.
        // Both effects show in the printed table; the summary uses the linear region.
        int trimTail = 5;
        int linearEnd = actualPages - trimTail;
        for (int i = 0; i < actualPages; i++) {
            if (!orPlainMs.get(i).isFullPage(pageSize)
                    || !orSmartMs.get(i).isFullPage(pageSize)
                    || !rvMs.get(i).isFullPage(pageSize)) {
                linearEnd = Math.min(linearEnd, i);
                break;
            }
        }

        // Print sparse table: first 5, every 10th in the middle, last 5.
        System.out.println();
        System.out.printf(Locale.ROOT,
                "=== Page-by-page Postgres-side metrics, pageSize=%d, pages=%d (linear region 1..%d) ===%n",
                pageSize, actualPages, linearEnd);
        System.out.printf(Locale.ROOT, "%-6s %10s %7s %10s %8s %10s %7s %s%n",
                "page", "OR pln ms", "OR buf", "OR sm ms", "ORs buf", "RV ms", "RV buf", "");
        for (int i = 0; i < actualPages; i++) {
            int p = i + 1;
            boolean printRow = p <= 5 || p > actualPages - 5 || p % 10 == 0;
            if (printRow) {
                String marker = "";
                if (!orPlainMs.get(i).isFullPage(pageSize)) marker = "  * partial";
                else if (p > linearEnd) marker = "  * trimmed tail";
                System.out.printf(Locale.ROOT, "%-6d %10.3f %7d %10.3f %8d %10.3f %7d%s%n",
                        p,
                        orPlainMs.get(i).timeMs(), orPlainMs.get(i).buffers(),
                        orSmartMs.get(i).timeMs(), orSmartMs.get(i).buffers(),
                        rvMs.get(i).timeMs(),     rvMs.get(i).buffers(),
                        marker);
            }
        }
        System.out.println();
        System.out.printf(Locale.ROOT,
                "(* trimmed tail — last %d pages excluded from summary medians;%n" +
                "  Postgres can switch to a custom plan at very deep cursors%n" +
                "  when keyset predicate becomes extremely selective.)%n", trimTail);

        // Compare medians of first 10 full pages vs last 10 of the linear region.
        List<Measurement> orPlainLin = orPlainMs.subList(0, linearEnd);
        List<Measurement> orSmartLin = orSmartMs.subList(0, linearEnd);
        List<Measurement> rvLin      = rvMs.subList(0, linearEnd);
        int linCount = orPlainLin.size();

        ShapeStats orPlain = new ShapeStats("OR plain ", orPlainLin, linCount);
        ShapeStats orSmart = new ShapeStats("OR smart ", orSmartLin, linCount);
        ShapeStats rv      = new ShapeStats("row-value", rvLin,      linCount);

        System.out.println();
        orPlain.print(linCount);
        orSmart.print(linCount);
        rv.print(linCount);

        // Weak asserts: catch dead/regressed code, not brittle to absolute timings.
        assertThat(linCount)
                .as("should have measured at least 50 full pages of pagination")
                .isGreaterThanOrEqualTo(50);
        assertThat(orPlain.tailTime)
                .as("OR plain Execution Time should grow with depth (head=%.3f tail=%.3f)",
                        orPlain.headTime, orPlain.tailTime)
                .isGreaterThan(orPlain.headTime * 1.5);
        assertThat(orSmart.tailTime)
                .as("OR smart Execution Time should stay roughly constant (head=%.3f tail=%.3f)",
                        orSmart.headTime, orSmart.tailTime)
                .isLessThan(orSmart.headTime * 3.0);
        assertThat(rv.tailTime)
                .as("row-value Execution Time should stay roughly constant (head=%.3f tail=%.3f)",
                        rv.headTime, rv.tailTime)
                .isLessThan(rv.headTime * 3.0);
        assertThat(orPlain.tailBuffers)
                .as("OR plain Buffers should grow with depth (head=%d tail=%d)",
                        orPlain.headBuffers, orPlain.tailBuffers)
                .isGreaterThan(orPlain.headBuffers * 2);
        assertThat(orSmart.tailBuffers)
                .as("OR smart Buffers should stay constant (head=%d tail=%d)",
                        orSmart.headBuffers, orSmart.tailBuffers)
                .isLessThanOrEqualTo(orSmart.headBuffers + 5);
        assertThat(rv.tailBuffers)
                .as("row-value Buffers should stay constant (head=%d tail=%d)",
                        rv.headBuffers, rv.tailBuffers)
                .isLessThanOrEqualTo(rv.headBuffers + 5);
    }

    private static class ShapeStats {
        final String label;
        final double headTime, tailTime;
        final int headBuffers, tailBuffers;

        ShapeStats(String label, List<Measurement> linear, int linCount) {
            this.label = label;
            List<Double> times = linear.stream().map(Measurement::timeMs).toList();
            this.headTime = median(times.subList(0, 10));
            this.tailTime = median(times.subList(linCount - 10, linCount));
            this.headBuffers = linear.subList(0, 10).stream().mapToInt(Measurement::buffers).max().orElse(0);
            this.tailBuffers = linear.subList(linCount - 10, linCount).stream().mapToInt(Measurement::buffers).max().orElse(0);
        }

        void print(int linCount) {
            System.out.printf(Locale.ROOT,
                    "%s  time pages 1-10 median = %7.3f ms   pages %d-%d median = %7.3f ms   growth = %.1fx%n",
                    label, headTime, linCount - 9, linCount, tailTime, tailTime / headTime);
            System.out.printf(Locale.ROOT,
                    "%s  buffers pages 1-10 max = %4d        pages %d-%d max    = %4d        growth = %.1fx%n",
                    label, headBuffers, linCount - 9, linCount, tailBuffers,
                    headBuffers == 0 ? 0.0 : (double) tailBuffers / headBuffers);
        }
    }

    private static class KeysetPosition {
        Timestamp ts;
        Long id;
        boolean exhausted;
    }

    private record Measurement(double timeMs, int buffers, int rowsReturned) {
        boolean isFullPage(int requestedSize) {
            return rowsReturned == requestedSize;
        }
    }

    private enum Shape { OR_PLAIN, OR_SMART, ROW_VALUE }

    private Measurement measureAndAdvance(KeysetPosition pos, String firstSql, String nextSql,
                                          Shape shape, int pageSize) {
        Object[] params;
        String sql;
        if (pos.ts == null) {
            // First page — no keyset condition, only LIMIT.
            sql = firstSql;
            params = new Object[]{pageSize};
        } else {
            sql = nextSql;
            params = switch (shape) {
                case OR_PLAIN  -> new Object[]{pos.ts, pos.ts, pos.id, pageSize};
                case OR_SMART  -> new Object[]{pos.ts, pos.ts, pos.id, pageSize};
                case ROW_VALUE -> new Object[]{pos.ts, pos.id, pageSize};
            };
        }

        List<String> plan = runExplain(sql, params);
        double ms = executionTimeMs(plan);
        int buffers = topNodeBuffers(plan);

        // EXPLAIN ANALYZE already ran the query but didn't return result rows.
        // One extra roundtrip is needed to advance the keyset position — its time is
        // not counted toward the measurement.
        List<Map<String, Object>> rows = jdbc.queryForList(sql, params);
        if (rows.isEmpty()) {
            pos.exhausted = true;
        } else {
            Map<String, Object> last = rows.get(rows.size() - 1);
            pos.ts = (Timestamp) last.get("created_at");
            pos.id = ((Number) last.get("id")).longValue();
        }
        return new Measurement(ms, buffers, rows.size());
    }

    private static final Pattern BUFFERS = Pattern.compile("Buffers: shared hit=(\\d+)(?: read=(\\d+))?");

    /**
     * Returns total buffers (hit + read) of the topmost plan node — the value before
     * the {@code Planning:} section. Matches what one reads at the top of the EXPLAIN.
     */
    private static int topNodeBuffers(List<String> plan) {
        for (String line : plan) {
            if (line.contains("Planning:")) break;
            Matcher m = BUFFERS.matcher(line);
            if (m.find()) {
                int hit = Integer.parseInt(m.group(1));
                int read = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
                return hit + read;
            }
        }
        return 0;
    }

    private List<String> runExplain(String sql, Object... params) {
        return jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) " + sql, String.class, params);
    }

    private static final Pattern EXEC_TIME = Pattern.compile("Execution Time:\\s+([0-9.]+)\\s+ms");

    private static double executionTimeMs(List<String> plan) {
        for (int i = plan.size() - 1; i >= 0; i--) {
            Matcher m = EXEC_TIME.matcher(plan.get(i));
            if (m.find()) {
                return Double.parseDouble(m.group(1));
            }
        }
        throw new IllegalStateException("No 'Execution Time' line in EXPLAIN output");
    }

    private static double median(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        return n % 2 == 0
                ? (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0
                : sorted.get(n / 2);
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
