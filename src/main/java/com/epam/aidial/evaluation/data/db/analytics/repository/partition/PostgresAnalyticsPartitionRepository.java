package com.epam.aidial.evaluation.data.db.analytics.repository.partition;

import com.epam.aidial.evaluation.data.db.analytics.model.PartitionInfo;
import com.epam.aidial.evaluation.data.db.analytics.repository.AnalyticsPartitionRepository;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Raw DDL/introspection for partitioned analytics tables. jOOQ has no partition-DDL API, so this
 * is the one place in the codebase — alongside {@code configuration.datasource} and
 * {@code service.infrastructure.health} — permitted to use a JDBC template directly instead of
 * the typed jOOQ DSL (see {@code JdbcTemplateFenceTest}'s allowed-packages list and
 * {@code openspec/changes/partition-analytics-tables/design.md} D5). Partition names are always
 * built from constants plus a {@link java.time.YearMonth} (never user input) by callers, but are
 * still validated here as defense in depth before being interpolated into DDL.
 */
@Slf4j
@Repository
@LogExecution
@RequiredArgsConstructor
@ConditionalOnProperty(name = "datasource.analytics.vendor", havingValue = "POSTGRES")
public class PostgresAnalyticsPartitionRepository implements AnalyticsPartitionRepository {

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-z0-9_]+$");
    private static final Pattern RANGE_BOUND_PATTERN =
            Pattern.compile("FOR VALUES FROM \\((MINVALUE|'?-?\\d+'?)\\) TO \\((MAXVALUE|'?-?\\d+'?)\\)");

    @Qualifier("analyticsJdbcTemplate")
    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Override
    public List<PartitionInfo> listPartitions(String parentTable) {
        validateIdentifier(parentTable);
        String sql = """
                SELECT child.relname AS partition_name, pg_get_expr(child.relpartbound, child.oid) AS bound
                FROM pg_inherits
                JOIN pg_class parent ON pg_inherits.inhparent = parent.oid
                JOIN pg_class child ON pg_inherits.inhrelid = child.oid
                JOIN pg_namespace n ON n.oid = parent.relnamespace
                WHERE parent.relname = :parentTable AND n.nspname = current_schema()
                """;
        return jdbcTemplate.query(
                sql,
                Map.of("parentTable", parentTable),
                (rs, rowNum) -> parsePartition(rs.getString("partition_name"), rs.getString("bound")));
    }

    @Override
    public void createRangePartition(String parentTable, String partitionName, long lowerBoundMs, long upperBoundMs) {
        validateIdentifier(parentTable);
        validateIdentifier(partitionName);
        String sql = "CREATE TABLE \"%s\" PARTITION OF \"%s\" FOR VALUES FROM ('%d') TO ('%d')"
                .formatted(partitionName, parentTable, lowerBoundMs, upperBoundMs);
        jdbcTemplate.getJdbcOperations().execute(sql);
        log.info("Created partition {} of {} for [{}, {})", partitionName, parentTable, lowerBoundMs, upperBoundMs);
    }

    @Override
    public void dropPartition(String partitionName) {
        validateIdentifier(partitionName);
        jdbcTemplate.getJdbcOperations().execute("DROP TABLE \"%s\"".formatted(partitionName));
        log.info("Dropped partition {}", partitionName);
    }

    @Override
    public void detachPartition(String parentTable, String partitionName) {
        validateIdentifier(parentTable);
        validateIdentifier(partitionName);
        jdbcTemplate
                .getJdbcOperations()
                .execute("ALTER TABLE \"%s\" DETACH PARTITION \"%s\"".formatted(parentTable, partitionName));
        log.info("Detached partition {} from {}", partitionName, parentTable);
    }

    @Override
    public long countRows(String partitionName) {
        validateIdentifier(partitionName);
        Long count = jdbcTemplate
                .getJdbcOperations()
                .queryForObject("SELECT count(*) FROM \"%s\"".formatted(partitionName), Long.class);
        return count == null ? 0L : count;
    }

    private static PartitionInfo parsePartition(String name, String bound) {
        if ("DEFAULT".equals(bound)) {
            return new PartitionInfo(name, null, null, true);
        }
        Matcher matcher = RANGE_BOUND_PATTERN.matcher(bound);
        if (!matcher.matches()) {
            throw new IllegalStateException("Unrecognized partition bound expression: " + bound);
        }
        return new PartitionInfo(name, parseBoundValue(matcher.group(1)), parseBoundValue(matcher.group(2)), false);
    }

    private static Long parseBoundValue(String token) {
        if ("MINVALUE".equals(token) || "MAXVALUE".equals(token)) {
            return null;
        }
        return Long.parseLong(token.replace("'", ""));
    }

    private static void validateIdentifier(String identifier) {
        if (!IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Invalid partition identifier: " + identifier);
        }
    }
}
