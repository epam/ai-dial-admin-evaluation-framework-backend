# Code Templates

Templates for adding new entities end-to-end: model, RecordMapper, repository, service, controller, migration, functional test, DTOs, and MapStruct mapper.

## New Domain Model
```java
package com.epam.aidial.evaluation.data.db.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewEntity {
    private UUID id;
    private String name;
    private String description;
    private String createdBy;
    private Long createdAt;  // epoch milliseconds
    private Long updatedAt;  // epoch milliseconds
}
```

## New RecordMapper

Maps a generated jOOQ `*Record` to the domain model. After adding a Flyway migration and running
`./gradlew generateJooq` (plus `./gradlew generateClickHouseJooq` for an analytics table, whose model has a ClickHouse twin), the generated `NewEntitiesRecord` becomes available.

```java
package com.epam.aidial.evaluation.data.db.mapper;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.jooq.meta.tables.records.NewEntitiesRecord;
import com.epam.aidial.evaluation.data.db.model.NewEntity;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@LogExecution
public class NewEntityRecordMapper {

    public NewEntity map(NewEntitiesRecord r) {
        return NewEntity.builder()
                .id(UUID.fromString(r.getId()))
                .name(r.getName())
                .description(r.getDescription())
                .createdBy(r.getCreatedBy())
                .createdAt(r.getCreatedAtMs())
                .updatedAt(r.getUpdatedAtMs())
                .build();
    }
}
```

For JSONB fields, the Record exposes a `JSONB` object — call `.data()` to get the raw String:
```java
.configSchema(r.getConfigSchema() != null ? r.getConfigSchema().data() : null)
```

## New Repository Interface

```java
package com.epam.aidial.evaluation.data.db.repository;

import com.epam.aidial.evaluation.data.db.model.NewEntity;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;

import java.util.Optional;
import java.util.UUID;

public interface NewEntityRepository {

    Page<NewEntity> findAll(PageRequest pageRequest);

    Optional<NewEntity> findById(UUID id);

    NewEntity save(NewEntity entity);

    long count();

    boolean deleteById(UUID id);

    boolean existsById(UUID id);
}
```

## New Repository Implementation (Postgres)

Uses the typed jOOQ DSL. After adding a migration and running the matching codegen task (`generateJooq` for the Postgres meta and analytics models; `generateClickHouseJooq` in addition for the ClickHouse analytics twin), the
generated `Tables.NEW_ENTITIES` constant and `NewEntitiesRecord` become available.

```java
package com.epam.aidial.evaluation.data.db.repository;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.mapper.NewEntityRecordMapper;
import com.epam.aidial.evaluation.data.db.model.NewEntity;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import com.epam.aidial.evaluation.data.db.repository.sql.FilterWhitelists;
import com.epam.aidial.evaluation.data.db.repository.sql.OrderByBuilder;
import com.epam.aidial.evaluation.data.db.repository.sql.PageRequestSqlBuilder;
import com.epam.aidial.evaluation.data.db.repository.sql.SortWhitelists;
import com.epam.aidial.evaluation.data.db.repository.sql.WhereBuilder;
import com.epam.aidial.evaluation.data.db.transaction.timestamp.TransactionTimestampContext;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.SortField;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.NEW_ENTITIES;

@Repository
@LogExecution
@RequiredArgsConstructor
@ConditionalOnProperty(name = "datasource.meta.vendor", havingValue = "POSTGRES")
public class PostgresNewEntityRepository implements NewEntityRepository {

    @Qualifier("metaDsl")
    private final DSLContext dsl;
    private final NewEntityRecordMapper recordMapper;
    private final TransactionTimestampContext transactionTimestampContext;
    private final WhereBuilder whereBuilder;
    private final OrderByBuilder orderByBuilder;

    @Override
    public Page<NewEntity> findAll(PageRequest pageRequest, List<FilterCondition> filters, boolean includeTotalCount) {
        Condition condition = whereBuilder.build(filters, FilterWhitelists.NEW_ENTITIES);
        long totalCount = includeTotalCount ? dsl.fetchCount(NEW_ENTITIES, condition) : -1;
        List<SortField<?>> orderBy = orderByBuilder.build(pageRequest.getSort(), SortWhitelists.NEW_ENTITIES);
        int limit = PageRequestSqlBuilder.limit(pageRequest);
        long offset = PageRequestSqlBuilder.offset(pageRequest);

        List<NewEntity> content = dsl.selectFrom(NEW_ENTITIES)
                .where(condition)
                .orderBy(orderBy)
                .limit(limit)
                .offset(offset)
                .fetch(recordMapper::map);

        return includeTotalCount ? Page.of(content, pageRequest, totalCount) : Page.withoutTotal(content, pageRequest);
    }

    @Override
    public Optional<NewEntity> findById(UUID id) {
        return dsl.selectFrom(NEW_ENTITIES)
                .where(NEW_ENTITIES.ID.eq(id.toString()))
                .fetchOptional(recordMapper::map);
    }

    @Override
    public NewEntity save(NewEntity entity) {
        long timestamp = transactionTimestampContext.getTimestamp();
        if (entity.getId() == null) {
            entity.setId(UUID.randomUUID());
        }
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(timestamp);
        }
        entity.setUpdatedAt(timestamp);

        dsl.insertInto(NEW_ENTITIES)
                .set(NEW_ENTITIES.ID, entity.getId().toString())
                .set(NEW_ENTITIES.NAME, entity.getName())
                .set(NEW_ENTITIES.DESCRIPTION, entity.getDescription())
                .set(NEW_ENTITIES.CREATED_BY, entity.getCreatedBy())
                .set(NEW_ENTITIES.CREATED_AT_MS, entity.getCreatedAt())
                .set(NEW_ENTITIES.UPDATED_AT_MS, entity.getUpdatedAt())
                .execute();
        return entity;
    }

    @Override
    public boolean deleteById(UUID id) {
        return dsl.deleteFrom(NEW_ENTITIES).where(NEW_ENTITIES.ID.eq(id.toString())).execute() > 0;
    }

    @Override
    public boolean existsById(UUID id) {
        return dsl.fetchExists(NEW_ENTITIES, NEW_ENTITIES.ID.eq(id.toString()));
    }

    @Override
    public long count() {
        return dsl.fetchCount(NEW_ENTITIES);
    }
}
```

### Batch insert with ON CONFLICT DO NOTHING (analytics pattern)

```java
var queries = items.stream()
    .map(item -> dsl.insertInto(MY_TABLE)
        .set(MY_TABLE.ID, item.getId().toString())
        .set(MY_TABLE.VALUE, item.getValue())
        .onConflict(MY_TABLE.ID).doNothing())
    .toList();
dsl.batch(queries).execute();
```

### Update with RETURNING (optimistic locking pattern)

```java
MyTableRecord updated = dsl.update(MY_TABLE)
    .set(MY_TABLE.NAME, newName)
    .set(MY_TABLE.VERSION, MY_TABLE.VERSION.add(1))
    .set(MY_TABLE.UPDATED_AT_MS, now)
    .where(MY_TABLE.ID.eq(id.toString()).and(MY_TABLE.VERSION.eq(currentVersion)))
    .returning()
    .fetchOne();
if (updated == null) {
    throw new OptimisticLockException("Stale version for id " + id);
}
return recordMapper.map(updated);
```

### Registering new filter/sort whitelists

Add to `FilterWhitelists` with a typed `Field<?>` from the generated table:

```java
public static final FilterSpec NEW_ENTITIES = FilterSpec.of(Map.of(
    "name", FilterFieldDefinition.of(
        NewEntities.NEW_ENTITIES.NAME,
        FilterFieldType.STRING, EnumSet.of(FilterOperator.EQ, FilterOperator.CO, FilterOperator.IN)),
    "createdAt", FilterFieldDefinition.of(
        NewEntities.NEW_ENTITIES.CREATED_AT_MS,
        FilterFieldType.LONG, EnumSet.of(
            FilterOperator.GT, FilterOperator.GTE, FilterOperator.LT, FilterOperator.LTE))
));
```

Add to `SortWhitelists` similarly with typed `Field<?>` references.

## New Service

A domain service injects only its own domain's repository. For data owned by another domain,
inject that domain's *service*, not its repository — see
[best-practices spec](../openspec/specs/best-practices/spec.md).

```java
package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.data.db.model.NewEntity;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import com.epam.aidial.evaluation.data.db.repository.NewEntityRepository;
import com.epam.aidial.evaluation.service.domain.dto.NewEntityRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.NewEntityResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.page.PageResponseDto;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.mapper.NewEntityMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NewEntityService {

    // Own-domain repository — the only repository this service may inject.
    private final NewEntityRepository repository;
    private final NewEntityMapper mapper;
    private final AuthorResolver authorResolver;

    // Sibling-domain services — inject these when the operation needs data or behavior
    // owned by another domain. NEVER inject SiblingDomainRepository here.
    // private final SiblingDomainService siblingDomainService;

    @Transactional
    public NewEntityResponseDto create(NewEntityRequestDto dto, Jwt jwt) {
        log.info("Creating new entity: {}", dto.getName());
        NewEntity entity = mapper.toEntity(dto, authorResolver.getCreatedBy(jwt));
        return mapper.toDto(repository.save(entity));
    }

    @Transactional(readOnly = true)
    public NewEntityResponseDto getById(UUID id) {
        return repository.findById(id)
            .map(mapper::toDto)
            .orElseThrow(() -> new EntityNotFoundException("Entity not found: " + id));
    }

    @Transactional(readOnly = true)
    public PageResponseDto<NewEntityResponseDto> getAll(int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        return PageResponseDto.from(repository.findAll(pageRequest), mapper::toDto);
    }
}
```

## DTO Separation Pattern (Standard)

**Always use separate Request and Response DTOs** for clear API contracts:

| DTO Type | Purpose | Contains |
|----------|---------|----------|
| `*RequestDto` | Client input for create/update | Mutable fields only, validation annotations |
| `*ResponseDto` | Server output | All fields including id, timestamps, computed values |
| `*PatchDto` | Partial updates (optional) | All fields nullable, no validation on optional fields |

**Benefits:**
- Clear contract separation
- Different validation rules per operation
- Better OpenAPI/Swagger documentation
- Type safety at compile time
- Independent evolution of request/response

## New Request DTO
```java
package com.epam.aidial.evaluation.service.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewEntityRequestDto {

    @NotBlank(message = "Name is required")
    @Size(max = 255, message = "Name must be less than 255 characters")
    private String name;

    @Size(max = 2000, message = "Description must be less than 2000 characters")
    private String description;
}
```

## New Response DTO
```java
package com.epam.aidial.evaluation.service.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewEntityResponseDto {

    private UUID id;
    private String name;
    private String description;
    private String createdBy;
    private Long createdAt;  // Epoch milliseconds (consistent across all APIs)
    private Long updatedAt;  // Epoch milliseconds (consistent across all APIs)
}
```

**API Timestamp Convention**: All timestamps in REST API responses use **epoch milliseconds (Long)** for performance and consistency. Do NOT convert to `Instant` or ISO 8601 strings in DTOs.

## New MapStruct Mapper
```java
package com.epam.aidial.evaluation.service.domain.mapper;

import com.epam.aidial.evaluation.data.db.model.NewEntity;
import com.epam.aidial.evaluation.service.domain.dto.NewEntityRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.NewEntityResponseDto;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface NewEntityMapper {

    // Model (Long timestamps) -> Response DTO (Long timestamps) - direct mapping
    NewEntityResponseDto toDto(NewEntity entity);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    NewEntity toEntity(NewEntityRequestDto dto, String createdBy);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    void update(@MappingTarget NewEntity entity, NewEntityRequestDto dto);
}
```

## New Controller
```java
package com.epam.aidial.evaluation.web.controller;

import com.epam.aidial.evaluation.service.domain.NewEntityService;
import com.epam.aidial.evaluation.service.domain.dto.NewEntityRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.NewEntityResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.page.PageResponseDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.hibernate.validator.constraints.Range;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/new-entities")
@RequiredArgsConstructor
@Tag(name = "New Entities", description = "New Entity management endpoints")
public class NewEntityController {

    private final NewEntityService service;

    @PostMapping
    @Operation(summary = "Create a new entity")
    @ResponseStatus(HttpStatus.CREATED)
    public NewEntityResponseDto create(
            @Valid @RequestBody NewEntityRequestDto dto,
            @AuthenticationPrincipal Jwt jwt) {
        return service.create(dto, jwt);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get entity by ID")
    public NewEntityResponseDto getById(
            @Parameter(description = "Entity ID") @PathVariable UUID id) {
        return service.getById(id);
    }

    @GetMapping
    @Operation(summary = "Get all entities (paginated)")
    public PageResponseDto<NewEntityResponseDto> getAll(
            @Parameter(description = "Page number (0-based)")
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size (1-100)")
            @RequestParam(defaultValue = "20") @Range(min = 1, max = 100) int size) {
        return service.getAll(page, size);
    }
}
```

## New Flyway Migration
```sql
-- V1.2__CreateNewEntitiesTable.sql
CREATE TABLE IF NOT EXISTS new_entities (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(2000),
    created_by VARCHAR(255) NOT NULL,
    created_at_ms BIGINT NOT NULL,
    updated_at_ms BIGINT NOT NULL
);

-- Create index on name for faster lookups
CREATE INDEX IF NOT EXISTS idx_new_entities_name ON new_entities(name);

-- Create index on created_at_ms for ordering
CREATE INDEX IF NOT EXISTS idx_new_entities_created_at_ms ON new_entities(created_at_ms DESC);
```

## New Functional Test
```java
package com.epam.aidial.evaluation.functional.tests;

import com.epam.aidial.evaluation.functional.BaseFunctionalTest;
import com.epam.aidial.evaluation.functional.PostgresFunctionalTests;
import com.epam.aidial.evaluation.service.domain.dto.NewEntityRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.NewEntityResponseDto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

@PostgresFunctionalTests
@DisplayName("NewEntity Controller Tests")
class NewEntityFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @BeforeEach
    void setUp() {
        // Use MetaTestDataHelper for fixture setup; do not inject JdbcTemplate directly
        metaTestDataHelper.deleteAllNewEntities();
    }

    @Test
    @DisplayName("Should create entity")
    void shouldCreateEntity() {
        var request = NewEntityRequestDto.builder()
                .name("Test Entity")
                .description("Test description")
                .build();

        var response = restTemplate.postForEntity(
                apiUrl("/new-entities"), jsonEntity(request), NewEntityResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getId()).isNotNull();
        assertThat(response.getBody().getName()).isEqualTo("Test Entity");
        assertThat(response.getBody().getCreatedAt()).isNotNull();
    }
}
```
