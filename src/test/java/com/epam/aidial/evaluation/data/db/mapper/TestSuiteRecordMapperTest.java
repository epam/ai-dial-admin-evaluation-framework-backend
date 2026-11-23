package com.epam.aidial.evaluation.data.db.mapper;

import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.TEST_SUITES;
import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.runner.model.SuiteType;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TestSuiteRecordMapperTest {
    private final DSLContext dsl = DSL.using(SQLDialect.POSTGRES);
    private final TestSuiteRecordMapper mapper = new TestSuiteRecordMapper();

    @Test
    @DisplayName("maps the seven suite projection fields and retains omitted field defaults")
    void mapsSuiteProjection() {
        final Record record = projection();
        record.set(TEST_SUITES.DEPLOYMENT_REF, JSONB.valueOf("{\"deployment\":1}"));
        record.set(TEST_SUITES.ROUTES_REF, JSONB.valueOf("{\"route\":2}"));
        record.set(TEST_SUITES.MCP_DEPLOYMENT_REF, JSONB.valueOf("{\"mcp\":3}"));
        record.set(TEST_SUITES.TOOL_REF, JSONB.valueOf("{\"tool\":4}"));

        assertThat(mapper.mapProjection(record))
                .isEqualTo(TestSuite.builder()
                        .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                        .name("suite")
                        .suiteType(SuiteType.MCP_TOOL)
                        .deploymentRef("{\"deployment\":1}")
                        .routesRef("{\"route\":2}")
                        .mcpDeploymentRef("{\"mcp\":3}")
                        .toolRef("{\"tool\":4}")
                        .build());
    }

    @Test
    @DisplayName("preserves null suite references in the seven-field projection")
    void preservesNullReferences() {
        final TestSuite mapped = mapper.mapProjection(projection());

        assertThat(mapped.getDeploymentRef()).isNull();
        assertThat(mapped.getRoutesRef()).isNull();
        assertThat(mapped.getMcpDeploymentRef()).isNull();
        assertThat(mapped.getToolRef()).isNull();
    }

    private Record projection() {
        final Record record = dsl.newRecord(
                TEST_SUITES.ID,
                TEST_SUITES.NAME,
                TEST_SUITES.SUITE_TYPE,
                TEST_SUITES.DEPLOYMENT_REF,
                TEST_SUITES.ROUTES_REF,
                TEST_SUITES.MCP_DEPLOYMENT_REF,
                TEST_SUITES.TOOL_REF);
        record.set(TEST_SUITES.ID, "00000000-0000-0000-0000-000000000001");
        record.set(TEST_SUITES.NAME, "suite");
        record.set(TEST_SUITES.SUITE_TYPE, "MCP_TOOL");
        return record;
    }
}
