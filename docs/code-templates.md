# Code Templates

Templates for adding a new meta-DB entity end-to-end: migration, model, RecordMapper, repository,
service, DTOs, mapper, controller, functional test. They are modelled on the Dataset vertical
(`DatasetController`, `DatasetService`, `DatasetRepository` / `PostgresDatasetRepository`,
`DatasetRecordMapper`, `DatasetRequestDto` / `DatasetResponseDto`, `DatasetMapper`,
`DatasetCrudFunctionalTests`) — read those when a template is not enough.

Formatting is owned by Spotless + palantir-java-format: run `./gradlew spotlessApply` after
copying a template; do not hand-format.

Every Spring component (`@Component`, `@Service`, `@Repository`, `@RestController`) carries
`@LogExecution` from `com.epam.aidial.evaluation.runner.config.logging.LogExecution` (see the
exceptions in AGENTS.md).

## New Flyway Migration

Path: `src/main/resources/db/migration/meta/POSTGRES/V1.<next>__CreateNewEntitiesTable.sql` —
`<next>` is one above the highest existing `V1.*` file (check the directory; do not reuse a
version). After adding it:

1. Run `./gradlew generateJooq` and commit the generated diff under `src/main/java-generated/`
   (never edit those files by hand).
2. Update `docs/database-schema.md`.

```sql
CREATE TABLE IF NOT EXISTS new_entities (
    id VARCHAR(36) PRIMARY KEY,           -- UUIDs are stored as VARCHAR(36)
    name VARCHAR(255) NOT NULL,
    description VARCHAR(2000),
    version BIGINT NOT NULL DEFAULT 0,    -- optimistic lock (see "Update with optimistic locking")
    created_by VARCHAR(255) NOT NULL,
    created_at_ms BIGINT NOT NULL,        -- epoch milliseconds
    updated_at_ms BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_new_entities_created_at_ms ON new_entities (created_at_ms DESC);
```

## New Domain Model

```java
package com.epam.aidial.evaluation.data.db.model;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewEntity {
    private UUID id;
    private String name;
    private String description;
    private Long version; // optimistic lock
    private String createdBy;
    private Long createdAt; // epoch milliseconds
    private Long updatedAt; // epoch milliseconds
}
```

## New RecordMapper

Maps the generated jOOQ `NewEntitiesRecord` (available after `./gradlew generateJooq`) to the model.

```java
package com.epam.aidial.evaluation.data.db.mapper;

import com.epam.aidial.evaluation.data.db.jooq.meta.tables.records.NewEntitiesRecord;
import com.epam.aidial.evaluation.data.db.model.NewEntity;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
@LogExecution
public class NewEntityRecordMapper {

    public NewEntity map(NewEntitiesRecord r) {
        return NewEntity.builder()
                .id(UUID.fromString(r.getId()))
                .name(r.getName())
                .description(r.getDescription())
                .version(r.getVersion())
                .createdBy(r.getCreatedBy())
                .createdAt(r.getCreatedAtMs())
                .updatedAt(r.getUpdatedAtMs())
                .build();
    }
}
```

JSONB columns come back as `org.jooq.JSONB`; the model keeps a `String` (`jsonb != null ? jsonb.data() : null`,
see `DatasetRecordMapper.toJsonString`).

## New Repository Interface

```java
package com.epam.aidial.evaluation.data.db.repository;

import com.epam.aidial.evaluation.data.db.model.NewEntity;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NewEntityRepository {

    Page<NewEntity> findAll(PageRequest pageRequest, List<FilterCondition> filters, boolean includeTotalCount);

    Optional<NewEntity> findById(UUID id);

    NewEntity save(NewEntity entity);

    long count();

    boolean deleteById(UUID id);

    boolean existsById(UUID id);
}
```

## New Repository Implementation (Postgres)

Typed jOOQ DSL on the meta `DSLContext`; timestamps come from `TransactionTimestampContext` (one
shared value per transaction), never from `Instant.now()`.

```java
package com.epam.aidial.evaluation.data.db.repository;

import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.NEW_ENTITIES;

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
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.SortField;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

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
        final Condition condition = whereBuilder.build(filters, FilterWhitelists.NEW_ENTITIES);
        final long totalCount = includeTotalCount ? dsl.fetchCount(NEW_ENTITIES, condition) : -1;
        final List<SortField<?>> orderBy = orderByBuilder.build(pageRequest.getSort(), SortWhitelists.NEW_ENTITIES);

        final List<NewEntity> content = dsl.selectFrom(NEW_ENTITIES)
                .where(condition)
                .orderBy(orderBy)
                .limit(PageRequestSqlBuilder.limit(pageRequest))
                .offset(PageRequestSqlBuilder.offset(pageRequest))
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
        final long now = transactionTimestampContext.getTimestamp();
        entity.setId(UUID.randomUUID());
        entity.setVersion(0L);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        dsl.insertInto(NEW_ENTITIES)
                .set(NEW_ENTITIES.ID, entity.getId().toString())
                .set(NEW_ENTITIES.NAME, entity.getName())
                .set(NEW_ENTITIES.DESCRIPTION, entity.getDescription())
                .set(NEW_ENTITIES.VERSION, entity.getVersion())
                .set(NEW_ENTITIES.CREATED_BY, entity.getCreatedBy())
                .set(NEW_ENTITIES.CREATED_AT_MS, entity.getCreatedAt())
                .set(NEW_ENTITIES.UPDATED_AT_MS, entity.getUpdatedAt())
                .execute();
        return entity;
    }

    @Override
    public long count() {
        return dsl.fetchCount(NEW_ENTITIES);
    }

    @Override
    public boolean deleteById(UUID id) {
        return dsl.deleteFrom(NEW_ENTITIES).where(NEW_ENTITIES.ID.eq(id.toString())).execute() > 0;
    }

    @Override
    public boolean existsById(UUID id) {
        return dsl.fetchExists(NEW_ENTITIES, NEW_ENTITIES.ID.eq(id.toString()));
    }
}
```

`save` above is insert-only. For create-or-update, branch on `entity.getId() == null` into private
`create` / `update` methods as `PostgresDatasetRepository.save` does.

### Update with optimistic locking

Throws `com.epam.aidial.evaluation.data.db.exception.OptimisticLockException` (the service maps it
to `VersionConflictException` → 409):

```java
import com.epam.aidial.evaluation.data.db.exception.OptimisticLockException;
import com.epam.aidial.evaluation.data.db.jooq.meta.tables.records.NewEntitiesRecord;

final NewEntitiesRecord updated = dsl.update(NEW_ENTITIES)
        .set(NEW_ENTITIES.NAME, entity.getName())
        .set(NEW_ENTITIES.VERSION, NEW_ENTITIES.VERSION.add(1))
        .set(NEW_ENTITIES.UPDATED_AT_MS, transactionTimestampContext.getTimestamp())
        .where(NEW_ENTITIES.ID.eq(entity.getId().toString()).and(NEW_ENTITIES.VERSION.eq(entity.getVersion())))
        .returning()
        .fetchOne();
if (updated == null) {
    throw new OptimisticLockException("Stale version for id " + entity.getId());
}
return recordMapper.map(updated);
```

### Batch insert with ON CONFLICT DO NOTHING (analytics pattern)

Analytics repositories use `@Qualifier("analyticsDsl") DSLContext` and the `analytics` generated tables.

```java
import org.jooq.Query;

final List<Query> queries = items.stream()
        .map(item -> (Query) dsl.insertInto(MY_TABLE)
                .set(MY_TABLE.ID, item.getId().toString())
                .set(MY_TABLE.VALUE, item.getValue())
                .onConflict(MY_TABLE.ID)
                .doNothing())
        .toList();
dsl.batch(queries).execute();
```

### Filter / sort whitelists

Add a `FilterSpec` to `FilterWhitelists` and a `SortSpec` to `SortWhitelists` (both in
`data.db.repository.sql`), mirroring `DATASETS`. Operators come from `FilterOperator`
(`EQ, NE, CO, GT, GE, LT, LE, IN, ...`).

```java
// FilterWhitelists
public static final FilterSpec NEW_ENTITIES = FilterSpec.of(Map.ofEntries(
        Map.entry(
                "name",
                FilterFieldDefinition.of(
                        NewEntities.NEW_ENTITIES.NAME,
                        FilterFieldType.STRING,
                        EnumSet.of(FilterOperator.EQ, FilterOperator.NE, FilterOperator.CO, FilterOperator.IN))),
        Map.entry(
                "createdAt",
                FilterFieldDefinition.of(
                        NewEntities.NEW_ENTITIES.CREATED_AT_MS,
                        FilterFieldType.LONG,
                        EnumSet.of(FilterOperator.GT, FilterOperator.GE, FilterOperator.LT, FilterOperator.LE)))));

// SortWhitelists
public static final SortSpec NEW_ENTITIES = SortSpec.of(
        Map.of("name", NewEntities.NEW_ENTITIES.NAME, "createdAt", NewEntities.NEW_ENTITIES.CREATED_AT_MS),
        List.of(SortKey.builder()
                .field("createdAt")
                .direction(PageRequest.SortDirection.DESC)
                .build()));
```

Then register the list path in `OpenApiQueryParamCustomizer.REGISTRY` so `filter`/`sort`/`page`/`size`
get generated docs:

```java
Map.entry(
        "/api/v1/new-entities",
        new EndpointParamConfig(
                FilterWhitelists.NEW_ENTITIES, SortWhitelists.NEW_ENTITIES, PaginationType.OFFSET)),
```

## New Service

All transaction management lives here, always with a qualified manager. A domain service injects
only its own domain's repository; for another domain's data inject that domain's *service* — see
[best-practices spec](../openspec/specs/best-practices/spec.md). Need the current time outside a
transaction timestamp? Inject `java.time.Clock` and use `clock.millis()`.

```java
package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.data.db.exception.InvalidFilterException;
import com.epam.aidial.evaluation.data.db.model.NewEntity;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import com.epam.aidial.evaluation.data.db.repository.NewEntityRepository;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.PageResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.NewEntityRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.NewEntityResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.page.PageResponseMapper;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.FilterValidationException;
import com.epam.aidial.evaluation.service.domain.filter.FilterParser;
import com.epam.aidial.evaluation.service.domain.mapper.NewEntityMapper;
import com.epam.aidial.evaluation.service.domain.sort.SortParser;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class NewEntityService {

    private final NewEntityRepository newEntityRepository; // own-domain repository only
    private final NewEntityMapper newEntityMapper;
    private final AuthorResolver authorResolver;
    private final SortParser sortParser;
    private final FilterParser filterParser;

    @Transactional(value = "metaTransactionManager", readOnly = true)
    public PageResponseDto<NewEntityResponseDto> getAll(
            int page, int size, List<String> sort, List<String> filter, boolean includeTotalCount) {
        final PageRequest pageRequest = PageRequest.builder()
                .page(page)
                .size(size)
                .sort(sortParser.parse(sort != null ? sort : List.of()))
                .build();
        final List<FilterCondition> filters = filterParser.parse(filter != null ? filter : List.of());
        try {
            final Page<NewEntity> result = newEntityRepository.findAll(pageRequest, filters, includeTotalCount);
            return PageResponseMapper.from(result, newEntityMapper::toDto, includeTotalCount);
        } catch (InvalidFilterException ex) {
            throw new FilterValidationException(ex.getMessage(), ex.getDetails());
        }
    }

    @Transactional(value = "metaTransactionManager", readOnly = true)
    public NewEntityResponseDto getById(UUID id) {
        return newEntityRepository
                .findById(id)
                .map(newEntityMapper::toDto)
                .orElseThrow(() -> new EntityNotFoundException("NewEntity not found with id: " + id));
    }

    @Transactional("metaTransactionManager")
    public NewEntityResponseDto create(NewEntityRequestDto requestDto, Jwt jwt) {
        log.info("Creating NewEntity with name: {}", requestDto.getName());
        final NewEntity entity = newEntityMapper.toEntity(requestDto, authorResolver.getCreatedBy(jwt));
        return newEntityMapper.toDto(newEntityRepository.save(entity));
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

Every DTO field carries `@Schema(example = "…")` for the OpenAPI spec. Length limits come from a
constants class (e.g. `ValidationConstants`), not repeated literals.

## New Request DTO
```java
package com.epam.aidial.evaluation.service.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
    @Schema(example = "My Entity")
    private String name;

    @Size(max = 2000, message = "Description must be less than 2000 characters")
    @Schema(example = "Entity used for regression tests")
    private String description;
}
```

## New Response DTO
```java
package com.epam.aidial.evaluation.service.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewEntityResponseDto {

    @Schema(example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID id;

    @Schema(example = "My Entity")
    private String name;

    @Schema(example = "Entity used for regression tests")
    private String description;

    @Schema(example = "user@example.com")
    private String createdBy;

    @Schema(example = "1735689600000")
    private Long createdAt; // epoch milliseconds

    @Schema(example = "1735689600000")
    private Long updatedAt; // epoch milliseconds
}
```

**API Timestamp Convention**: timestamps are **epoch milliseconds (Long)** end to end — no `Instant`
or ISO 8601 in DTOs. JSONB schema fields are exposed as `Map<String, Object>` in response DTOs
(converted via `JsonbMapper.mapJsonSchema`), while the model keeps `String`.

## New Mapper

Plain field-copy mappers use MapStruct (e.g. `MetricDeclarationMapper`). When mapping needs JSONB
conversion or other injected collaborators, write a `@Component @LogExecution` class instead, as
`DatasetMapper` does.

```java
package com.epam.aidial.evaluation.service.domain.mapper;

import com.epam.aidial.evaluation.data.db.model.NewEntity;
import com.epam.aidial.evaluation.service.domain.dto.NewEntityRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.NewEntityResponseDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface NewEntityMapper {

    NewEntityResponseDto toDto(NewEntity entity);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    NewEntity toEntity(NewEntityRequestDto dto, String createdBy);
}
```

## New Controller

Mirrors `DatasetController`: `@Validated`, `@LogExecution`, page/size resolved by
`PaginationParamResolver`, `filter` bound with `@FilterParam`, `sort` capped with `@Size`.
Deliberately differs on imports: `DatasetController` imports swagger's
`io.swagger.v3.oas.annotations.parameters.RequestBody` and so has to write Spring's as the FQN
`@org.springframework.web.bind.annotation.RequestBody`. This template imports only Spring's `@RequestBody`
(no FQN) — use `@Operation(requestBody = ...)` only when you need the swagger one.

```java
package com.epam.aidial.evaluation.web.controller;

import com.epam.aidial.evaluation.constants.ValidationConstants;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.PageResponseDto;
import com.epam.aidial.evaluation.service.domain.NewEntityService;
import com.epam.aidial.evaluation.service.domain.dto.NewEntityRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.NewEntityResponseDto;
import com.epam.aidial.evaluation.web.pagination.FilterParam;
import com.epam.aidial.evaluation.web.pagination.PaginationParamResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@LogExecution
@Validated
@RequestMapping("/api/v1/new-entities")
@RequiredArgsConstructor
@Tag(name = "New Entities", description = "NewEntity management endpoints")
public class NewEntityController {

    private final NewEntityService newEntityService;
    private final PaginationParamResolver paginationParamResolver;

    @GetMapping
    @Operation(summary = "Get all new entities", description = "Retrieves new entities with pagination")
    @ApiResponse(
            responseCode = "200",
            description = "New entities retrieved successfully",
            content =
                    @Content(mediaType = "application/json", schema = @Schema(implementation = PageResponseDto.class)))
    public PageResponseDto<NewEntityResponseDto> getAll(
            @Parameter(description = "Page number") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size") @RequestParam(required = false) Integer size,
            @Parameter(description = "Sort keys")
                    @RequestParam(name = "sort", required = false)
                    @Size(max = ValidationConstants.MAX_LIST_SORT_PARAMS)
                    List<String> sort,
            @Parameter(description = "Filter conditions") @FilterParam List<String> filter,
            @Parameter(description = "When true, includes totalElements and totalPages. Default: false.")
                    @RequestParam(defaultValue = "false")
                    boolean includeTotalCount) {
        final int resolvedPage = paginationParamResolver.resolvePage(page);
        final int resolvedSize = paginationParamResolver.resolveSize(size);
        return newEntityService.getAll(resolvedPage, resolvedSize, sort, filter, includeTotalCount);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a new entity by ID")
    @ApiResponse(
            responseCode = "200",
            description = "NewEntity found",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = NewEntityResponseDto.class)))
    @ApiResponse(responseCode = "404", description = "NewEntity not found")
    public NewEntityResponseDto getById(@Parameter(description = "NewEntity ID") @PathVariable UUID id) {
        return newEntityService.getById(id);
    }

    @PostMapping
    @Operation(summary = "Create a new entity")
    @ApiResponse(
            responseCode = "201",
            description = "NewEntity created",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = NewEntityResponseDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request body")
    @ResponseStatus(HttpStatus.CREATED)
    public NewEntityResponseDto create(
            @Valid @RequestBody NewEntityRequestDto requestDto, @AuthenticationPrincipal Jwt jwt) {
        return newEntityService.create(requestDto, jwt);
    }
}
```

Operation-level examples go in `src/main/resources/openapi/examples/`, named after the registered
path: `{pathKey}-{method}-{request|response-{status}}-{name}.json`, e.g.
`api-v1-new-entities-POST-request-minimal.json`, `api-v1-new-entities-POST-response-201-full.json`
(compare `api-v1-datasets-POST-*`). Non-trivial endpoints need `minimal` + `full`. See the
[openapi-examples spec](../openspec/specs/openapi-examples/spec.md).

## New Functional Test

An `abstract` class in `functional.tests` extending `BaseFunctionalTest` (same package), run by
registering a `@Nested` subclass inside `PostgresFunctionalTests` (a class, not an annotation).
Create fixtures through `MetaTestDataHelper` (add a `createNewEntity(...)` helper there — SQL stays
in helpers) and assert state through the repository; never inject `JdbcTemplate`. Use unique names
(`UUID.randomUUID()`) so tests do not depend on cleanup order.

```java
package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.repository.NewEntityRepository;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.service.domain.dto.NewEntityRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.NewEntityResponseDto;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@DisplayName("NewEntity Functional Tests")
public abstract class NewEntityFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private NewEntityRepository newEntityRepository;

    @Test
    @DisplayName("POST /new-entities returns 201 and persists the entity")
    void createReturns201AndPersists() {
        final NewEntityRequestDto request = NewEntityRequestDto.builder()
                .name("NewEntity-" + UUID.randomUUID())
                .description("smoke")
                .build();

        final ResponseEntity<NewEntityResponseDto> response =
                restTemplate.postForEntity(apiUrl("/new-entities"), jsonEntity(request), NewEntityResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getName()).isEqualTo(request.getName());
        assertThat(newEntityRepository.findById(response.getBody().getId())).isPresent();
    }
}
```

Registration in `src/test/java/com/epam/aidial/evaluation/functional/PostgresFunctionalTests.java`:

```java
@Nested
class NewEntityTests extends NewEntityFunctionalTests {}
```

Run it: `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$NewEntityTests"`.
