package com.epam.aidial.evaluation.data.db.repository;

import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.METRIC_SCORE_DEFINITION;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.mapper.MetricScoreDefinitionRecordMapper;
import com.epam.aidial.evaluation.data.db.model.MetricScoreDefinition;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Slf4j
@Repository
@LogExecution
@RequiredArgsConstructor
@ConditionalOnProperty(name = "datasource.meta.vendor", havingValue = "POSTGRES")
public class PostgresMetricScoreDefinitionRepository implements MetricScoreDefinitionRepository {

    @Qualifier("metaDsl")
    private final DSLContext dsl;

    private final MetricScoreDefinitionRecordMapper recordMapper;

    @Override
    public List<MetricScoreDefinition> findAll() {
        return dsl.selectFrom(METRIC_SCORE_DEFINITION)
                .orderBy(METRIC_SCORE_DEFINITION.NAME.asc())
                .fetch(recordMapper::map);
    }
}
