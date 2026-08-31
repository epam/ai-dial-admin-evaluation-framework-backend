package com.epam.aidial.evaluation.functional.config.persistence;

import java.io.IOException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Meta-database snapshot/restore shared by every vendor's {@link TestPersistenceService}: the meta DB is
 * always Postgres, so {@code pg_dump}/{@code pg_restore} inside the meta container plus a
 * {@code DROP/CREATE SCHEMA public} is vendor-independent. Only the analytics cleanup differs per vendor,
 * and that is left to subclasses via {@link #cleanupAnalyticsTables()}.
 */
public abstract class PostgresMetaSnapshotSupport implements TestPersistenceService {

    private static final String DUMP_FILE = "/tmp/test_dump.tar";

    private final JdbcTemplate metaJdbcTemplate;
    private final PostgreSQLContainer metaContainer;

    protected PostgresMetaSnapshotSupport(JdbcTemplate metaJdbcTemplate, PostgreSQLContainer metaContainer) {
        this.metaJdbcTemplate = metaJdbcTemplate;
        this.metaContainer = metaContainer;
    }

    /** Wipes every analytics table so that each test starts from an empty analytics dataset. */
    protected abstract void cleanupAnalyticsTables();

    /**
     * Runs {@link #dropAndCreatePublicSchema()} through the Spring proxy so its {@code REQUIRES_NEW}
     * transaction actually applies; subclasses supply their own self-reference because the proxy is only
     * obtainable on the concrete bean type.
     */
    protected abstract void dropAndCreateSchemaTransactionally();

    @Override
    public void dumpDb() {
        runContainerCommand(
                String.format(
                        "pg_dump -Ft -U %s -f %s %s",
                        metaContainer.getUsername(), DUMP_FILE, metaContainer.getDatabaseName()),
                String.format("take a snapshot '%s'", DUMP_FILE));
    }

    @Override
    public void restoreDb() {
        dropAndCreateSchemaTransactionally();
        cleanupAnalyticsTables();
        runContainerCommand(
                String.format(
                        "pg_restore -Ft -U %s -d %s %s",
                        metaContainer.getUsername(), metaContainer.getDatabaseName(), DUMP_FILE),
                String.format("restore from a snapshot '%s'", DUMP_FILE));
    }

    @Transactional(value = "metaTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void dropAndCreatePublicSchema() {
        metaJdbcTemplate.execute("DROP SCHEMA public cascade;");
        metaJdbcTemplate.execute("CREATE SCHEMA public;");
    }

    @Override
    public void cleanupResources() {
        runContainerCommand(String.format("rm %s", DUMP_FILE), String.format("remove snapshot '%s'", DUMP_FILE));
    }

    private void runContainerCommand(String command, String description) {
        try {
            var result = metaContainer.execInContainer("sh", "-c", command);

            if (result.getExitCode() != 0) {
                throw new IllegalStateException("couldn't " + description + ": " + result.getStderr());
            }
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("couldn't " + description, e);
        }
    }
}
