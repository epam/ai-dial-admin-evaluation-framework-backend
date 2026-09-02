package com.epam.aidial.evaluation.configuration.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.epam.aidial.evaluation.configuration.properties.clickhouse.ClickHouseBackfillProperties;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ClickHouseAnalyticsBackfillMigration Tests")
class ClickHouseAnalyticsBackfillMigrationTest {

    @Test
    @DisplayName("Is a repeatable migration: version is null")
    void isRepeatable() {
        assertThat(migration(disabledProperties()).getVersion()).isNull();
    }

    @Test
    @DisplayName("Checksum is stable for identical configuration")
    void checksumStableForIdenticalConfig() {
        assertThat(migration(enabledProperties()).getChecksum())
                .isEqualTo(migration(enabledProperties()).getChecksum());
    }

    @Test
    @DisplayName("Checksum changes when the enabled flag flips, so enabling triggers a Flyway re-apply")
    void checksumChangesWhenEnabledFlips() {
        ClickHouseBackfillProperties toggled = enabledProperties();
        toggled.setEnabled(false);
        assertThat(migration(enabledProperties()).getChecksum())
                .isNotEqualTo(migration(toggled).getChecksum());
    }

    @Test
    @DisplayName("Checksum changes when the source host changes, but not when only the password changes")
    void checksumIgnoresPasswordButTracksSource() {
        ClickHouseBackfillProperties rehosted = enabledProperties();
        rehosted.getPostgres().setHost("other-host");
        assertThat(migration(enabledProperties()).getChecksum())
                .isNotEqualTo(migration(rehosted).getChecksum());

        ClickHouseBackfillProperties rotated = enabledProperties();
        rotated.getPostgres().setPassword("rotated-secret");
        assertThat(migration(enabledProperties()).getChecksum())
                .isEqualTo(migration(rotated).getChecksum());
    }

    @Test
    @DisplayName("When disabled, migrate() is a no-op that never touches the database connection")
    void disabledMigrationTouchesNothing() throws Exception {
        Context context = mock(Context.class);

        migration(disabledProperties()).migrate(context);

        verifyNoInteractions(context);
    }

    @Test
    @DisplayName("When enabled with missing source coordinates, migrate() fails fast naming every missing property")
    void enabledWithoutSourceFailsFast() {
        ClickHouseBackfillProperties properties = new ClickHouseBackfillProperties();
        properties.setEnabled(true);

        assertThatThrownBy(() -> migration(properties).migrate(mock(Context.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("clickhouse.analytics.backfill.postgres.host")
                .hasMessageContaining("clickhouse.analytics.backfill.postgres.port")
                .hasMessageContaining("clickhouse.analytics.backfill.postgres.database")
                .hasMessageContaining("clickhouse.analytics.backfill.postgres.schema")
                .hasMessageContaining("clickhouse.analytics.backfill.postgres.username");
    }

    private static ClickHouseAnalyticsBackfillMigration migration(ClickHouseBackfillProperties properties) {
        return new ClickHouseAnalyticsBackfillMigration(properties);
    }

    private static ClickHouseBackfillProperties disabledProperties() {
        ClickHouseBackfillProperties properties = enabledProperties();
        properties.setEnabled(false);
        return properties;
    }

    private static ClickHouseBackfillProperties enabledProperties() {
        ClickHouseBackfillProperties.Postgres postgres = new ClickHouseBackfillProperties.Postgres();
        postgres.setHost("pg-host");
        postgres.setPort(5432);
        postgres.setDatabase("evaluation_analytics_db");
        postgres.setSchema("public");
        postgres.setUsername("backfill_reader");
        postgres.setPassword("secret");
        ClickHouseBackfillProperties properties = new ClickHouseBackfillProperties();
        properties.setEnabled(true);
        properties.setPostgres(postgres);
        return properties;
    }
}
