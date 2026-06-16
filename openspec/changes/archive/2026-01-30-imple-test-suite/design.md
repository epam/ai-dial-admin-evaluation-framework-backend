## Context

The backend currently exposes basic CRUD for TestSuites, but the model is a temporary stub and does not represent real authoring needs:

- TestSuites must be bound to a deployment and a concrete endpoint contract so later jobs can invoke the endpoint deterministically.
- TestCases are large/complex datasets and should be managed via dedicated APIs (not nested inside TestSuite endpoints).
- Schemas must be first-class so we can (a) validate datasets, (b) reference schema parts in metrics later, and (c) drive analytics table generation.
- CSV import/export is required for authoring workflows.

Constraints:

- JDBC-only (no JPA), Postgres + Flyway migrations.
- Keep list endpoints consistent (pagination/sorting + reusable filtering semantics).
- No backward compatibility required with existing TestSuite API contract.

## Goals / Non-Goals

**Goals:**

- Implement a production-ready authoring model:
  - TestSuite embeds `deploymentRef` and `endpointRef` (OpenAPI 3.1 operation contract).
  - TestCasesDefinition properties are embedded in TestSuite; TestCases are managed via nested endpoints under TestSuite.
- Store all schema/payload documents as Postgres **`jsonb`**.
- Support CSV export/import for TestCasesDefinition with auto schema detection.
- Introduce a reusable filtering spec used by all list endpoints (structured `filter` DSL only in v1).
- Provide a list-only stub endpoint for metric definitions (full metric definition management is deferred).

**Non-Goals:**

- Executing evaluation runs, invoking endpoints during jobs, persisting run results.
- Full metric definition/versioning system and metric expression language.
- Advanced search/filtering (OR groups, nested jsonb-path filters, full-text search); we will design v1 and explicitly leave extension points.

## Decisions

### External Dependencies

New libraries to add to `build.gradle` for this change:

| Library | Artifact | Version | Purpose |
|---------|----------|---------|---------|
| Apache Commons CSV | `org.apache.commons:commons-csv` | 1.12.0 | CSV parsing and generation for test case import/export |
| JSON Schema Validator | `com.networknt:json-schema-validator` | 1.5.4 | JSON Schema validation (Draft-07) for test case data |
| Swagger Parser | `io.swagger.parser.v3:swagger-parser` | 2.1.25 | OpenAPI spec parsing and `$ref` resolution |

#### Apache Commons CSV

Used for CSV import/export functionality:
- RFC 4180 compliant parsing
- Streaming API for memory-efficient large file processing
- Configurable delimiters, quote chars, escape handling
- Header auto-detection and generation

#### JSON Schema Validator (networknt)

Used for validating test case `parameters` and `facts` against JSON Schema:
- JSON Schema Draft-07 compliance (widely adopted, good compatibility)
- Detailed validation error messages
- Efficient schema caching support
- Full support for `$ref`, `oneOf`, `anyOf`, `allOf`

**Alternative considered**: `everit-org/json-schema` - rejected as networknt has better maintenance and Spring ecosystem integration.

#### Swagger Parser

Used for parsing OpenAPI specifications during endpoint contract import:
- OpenAPI 3.x parsing
- Automatic `$ref` resolution (inline all references)
- Schema extraction from operation objects
- Based on `swagger-core` models

**Note**: Parser is used only during import to resolve `$ref`s. DTOs remain clean POJOs (`Map<String, Object>` for schemas).

#### Libraries NOT Needed

The following functionality is covered by existing dependencies:
- **JSON processing**: Jackson (via Spring Boot)
- **Bean validation**: Hibernate Validator (already present)
- **DTO mapping**: MapStruct 1.6.0 (already present)
- **String utilities**: Apache Commons Lang3 (already present)

### Configuration: DIAL Components

Add configuration property for DIAL Core component base URL (used to construct full endpoint URLs from deployment references):

```yaml
dial:
  components:
    core:
      base-url: ${DIAL_CORE_BASE_URL:http://localhost:8080}
```

Configuration properties class:

```java
@ConfigurationProperties(prefix = "dial.components.core")
public class DialCoreProperties {
    private String baseUrl;  // Base URL for DIAL Core component
}
```

### Type System: SchemaFieldDto

Define the type system used for schema field definitions across the application.

#### SchemaFieldDto Structure

```java
@Data
@Builder
public class SchemaFieldDto {
    @NotBlank
    private String name;              // Field name (required)
    
    @NotNull
    private SchemaFieldType type;     // Type enum (required)
    
    private boolean required;         // Default: false
    
    private String description;       // Optional, for UI hints
}
```

#### Allowed Types (enum `SchemaFieldType`)

| Type | Java Mapping | JSON Representation | CSV Inference Rule |
|------|--------------|---------------------|-------------------|
| `STRING` | `String` | `"value"` | Default fallback for any text |
| `INTEGER` | `Long` | `123` | Matches regex `^-?\d+$` |
| `NUMBER` | `Double` | `123.45` | Matches regex `^-?\d+\.?\d*$` |
| `BOOLEAN` | `Boolean` | `true`/`false` | Matches `true/false/1/0` (case-insensitive) |
| `OBJECT` | `Map<String, Object>` | `{...}` | **Not auto-detected from CSV** (requires schema) |
| `ARRAY` | `List<Object>` | `[...]` | **Not auto-detected from CSV** (requires schema) |

#### CSV Type Inference Rules

For auto-detection mode (when no schema is pre-defined):
- Only primitive types are inferred: `STRING`, `INTEGER`, `NUMBER`, `BOOLEAN`
- `OBJECT` and `ARRAY` types are **never auto-inferred** from CSV
- Cells containing JSON-like content (`{...}` or `[...]`) are stored as `STRING`

For schema-defined import (when schema exists):
- If field type is `OBJECT`: attempt JSON parsing of cell value into `Map<String, Object>`
- If field type is `ARRAY`: attempt JSON parsing of cell value into `List<Object>`
- If JSON parsing fails: mark `isValid = false` for that test case

#### Format Specifiers (Deferred to v2)

Format specifiers for `STRING` type (e.g., `date`, `date-time`, `email`, `uri`, `uuid`) are deferred to v2 to keep CSV inference simpler in v1.

### DTO Structure Strategy

To balance type safety with flexibility, we separate "Configuration" (Schema) from "Content" (Data).

#### A. Static "Configuration" Objects (The Schema)

These represent the `jsonb` columns in `test_suites`. They MUST be strongly typed POJOs.

##### DeploymentReferenceDto

Reference to an external deployment in DIAL system.

```java
@Data
@Builder
public class DeploymentReferenceDto {
    @NotBlank
    private String id;       // Required - deployment identifier in external system (string)
    
    @NotBlank
    private String name;     // Required - display name for UI
    
    private String version;  // Optional - deployment version
    
    // NOTE: URL is NOT stored here. 
    // Full URL is constructed at runtime: dial.components.core.base-url + relativeUrl
}
```

##### EndpointContractDto

Defines the API contract for an endpoint, modeled after OpenAPI 3.1 Operation Object.

```java
@Data
@Builder
public class EndpointContractDto {
    @NotNull
    private HttpMethod method;        // GET, POST, PUT, DELETE, PATCH
    
    @NotBlank
    private String relativeUrl;       // e.g., "/deployments/{deploymentId}/chat/completions"
    
    private String operationId;       // Optional, from OpenAPI spec
    
    private List<ParameterDefinitionDto> parameters;  // Query/path/header parameters
    
    private Map<String, Object> requestBodySchema;    // JSON Schema for request body
    
    private Map<String, Object> responseBodySchema;   // JSON Schema for response body
}
```

##### ParameterDefinitionDto

Defines a single endpoint parameter (query, path, or header).

```java
@Data
@Builder
public class ParameterDefinitionDto {
    @NotBlank
    private String name;              // Parameter name
    
    @NotNull
    private ParameterLocation in;     // QUERY, PATH, HEADER
    
    private boolean required;         // Default: false (except PATH params)
    
    private Map<String, Object> schema;  // JSON Schema for this parameter
}

public enum ParameterLocation {
    QUERY, PATH, HEADER
}
```

##### TestCasesDefinitionDto

Metadata defining the schema for test case datasets.

```java
@Data
@Builder
public class TestCasesDefinitionDto {
    // STORED in DB - user-defined ground truth columns
    private List<SchemaFieldDto> factFields;
    
    // COMPUTED at runtime from endpointRef - NOT stored in DB
    // Populated by service layer when reading TestSuite
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private List<SchemaFieldDto> parameterFields;
}
```

**IMPORTANT**: `parameterFields` is **computed, not stored**:
- Derived from `endpointRef.parameters` + `endpointRef.requestBodySchema`
- Extracted using `EndpointSchemaExtractor` utility at service layer
- Flattening is **top-level only**: nested objects in requestBodySchema remain as single `OBJECT` type fields
- Only `factFields` is persisted in `test_cases_definition` jsonb column

#### B. Dynamic "Data" Objects (The Content)

These represent the `jsonb` columns in `test_cases`. **Use separate Request/Response DTOs** per project standard.

##### TestCaseRequestDto (for create/update)

```java
@Data
@Builder
public class TestCaseRequestDto {
    @NotBlank
    @Size(max = 255)
    private String testCaseName;              // Required
    
    private Map<String, Object> parameters;   // Optional, default empty map
    private Map<String, Object> facts;        // Optional, default empty map
    private Boolean isEnabled;                // Optional, default true
    
    // NOTE: isValid is NOT accepted in requests - it's calculated
}
```

##### TestCaseResponseDto (for responses)

```java
@Data
@Builder
public class TestCaseResponseDto {
    private UUID id;
    private String testCaseName;
    private Map<String, Object> parameters;
    private Map<String, Object> facts;
    private boolean isEnabled;
    private boolean isValid;                          // Calculated, read-only
    
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<String> validationWarnings;          // Only included when includeWarnings=true
    
    private Long createdAt;                           // Epoch milliseconds
    private Long updatedAt;                           // Epoch milliseconds
}
```

**Notes**:
- `validationWarnings` is stored in DB but only included in response when `includeWarnings=true` query param is set
- Do not use `JsonNode` in DTOs; use `Map<String, Object>` to keep the Service layer independent of Jackson

### TestCase API Design

#### REST Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/test-suites/{testSuiteId}/test-cases` | Create test case |
| `GET` | `/api/v1/test-suites/{testSuiteId}/test-cases` | List test cases (paginated) |
| `GET` | `/api/v1/test-suites/{testSuiteId}/test-cases/{id}` | Get test case by ID |
| `PUT` | `/api/v1/test-suites/{testSuiteId}/test-cases/{id}` | Full replacement |
| `PATCH` | `/api/v1/test-suites/{testSuiteId}/test-cases/{id}` | Partial update (RFC 7396) |
| `DELETE` | `/api/v1/test-suites/{testSuiteId}/test-cases/{id}` | Delete single test case |
| `DELETE` | `/api/v1/test-suites/{testSuiteId}/test-cases` | Bulk delete (with filter) |

#### Scope Validation

All TestCase endpoints MUST validate that the `testCaseId` belongs to the specified `testSuiteId`:
- If `testCaseId` exists but belongs to a different suite → **404 Not Found**
- If `testCaseId` does not exist → **404 Not Found**
- If `testSuiteId` does not exist → **404 Not Found**

#### Required Fields on Create

| Field | Required | Default |
|-------|----------|---------|
| `testCaseName` | **Yes** | - |
| `parameters` | No | `{}` (empty map) |
| `facts` | No | `{}` (empty map) |
| `isEnabled` | No | `true` |

#### isValid Field Behavior

- **Read-only**: Ignored in all request bodies
- **Calculated**: Set by server based on schema validation
- **Validation warnings**: Optionally included in response when `isValid=false`

#### PATCH Allowed Fields

RFC 7396 JSON Merge Patch limited to:
- `testCaseName`
- `isEnabled`
- `parameters`
- `facts`

Fields NOT patchable: `id`, `isValid`, `createdAt`, `updatedAt`

#### Bulk Delete

`DELETE /api/v1/test-suites/{testSuiteId}/test-cases` supports:
- Query param filters: `?filter=isEnabled:eq:false&filter=isValid:eq:false`
- Returns count of deleted items
- If no filter provided, deletes ALL test cases in the suite (with confirmation?)

#### Filtering Whitelist (TestCases)

| Field | Operators | Type |
|-------|-----------|------|
| `testCaseName` | `eq`, `contains` | String |
| `isEnabled` | `eq` | Boolean |
| `isValid` | `eq` | Boolean |
| `createdAt` | `gt`, `lt`, `gte`, `lte` | Long (epoch ms) |

**Future (v2)**: Filtering on `parameters.*` and `facts.*` JSONB paths.

#### Sortable Fields (TestCases)

| Field | Default Direction |
|-------|-------------------|
| `testCaseName` | asc |
| `createdAt` | desc |
| `updatedAt` | desc |
| `isEnabled` | asc |
| `isValid` | asc |

**Future (v2)**: Sorting on `parameters.*` and `facts.*` JSONB paths.

#### Auto-generation of testCaseName

- **API calls**: `testCaseName` is **required** (no auto-generation)
- **CSV import fallback**: If `testCaseName` column is missing, generate sequential names: "Row 1", "Row 2", etc.

### JSON Schema Storage and Validation

#### Storage Format

JSON Schema objects (`requestBodySchema`, `responseBodySchema`, parameter `schema`) are stored as `Map<String, Object>` in DTOs and `jsonb` in PostgreSQL.

**Rationale**: 
- DTOs remain Jackson-independent (per design constraint)
- JSONB storage works naturally with Jackson serialization
- Validation logic is centralized in service layer

#### $ref Resolution: Always Resolve on Import

When importing or creating an endpoint contract with JSON Schema containing `$ref` references:
- **Always resolve all $refs during import** (use `swagger-parser` or similar)
- Store the **fully-resolved, self-contained schema** in the database
- No runtime $ref resolution needed

**Rationale**: For an evaluation framework, predictability is critical. When a TestSuite is created, the schema should be frozen to ensure consistent validation across all test runs.

#### Validation Library

Use `com.networknt:json-schema-validator` at service layer for runtime validation:

```java
@Service
public class SchemaValidationService {
    private final JsonSchemaFactory schemaFactory;
    
    public ValidationResult validate(Map<String, Object> data, 
                                      Map<String, Object> schemaMap) {
        // Convert Map to library's schema object for validation
        JsonSchema schema = schemaFactory.getSchema(
            objectMapper.valueToTree(schemaMap));
        
        Set<ValidationMessage> errors = schema.validate(
            objectMapper.valueToTree(data));
        
        return new ValidationResult(errors.isEmpty(), errors);
    }
}
```

### Endpoint Schema Extraction Utility

Service-layer utility to extract flattened `parameterFields` from `EndpointContractDto`.

```java
@Component
public class EndpointSchemaExtractor {
    
    /**
     * Extracts a flat list of parameter fields from endpoint contract.
     * Used for CSV export headers and validation schema generation.
     * 
     * Flattening rules:
     * - Query/path/header parameters: each becomes a SchemaFieldDto
     * - requestBodySchema: TOP-LEVEL properties only become SchemaFieldDto
     * - Nested objects remain as single OBJECT type field
     * - Arrays remain as single ARRAY type field
     */
    public List<SchemaFieldDto> extractParameterFields(EndpointContractDto endpoint) {
        List<SchemaFieldDto> fields = new ArrayList<>();
        
        // 1. Add query/path/header parameters
        if (endpoint.getParameters() != null) {
            for (ParameterDefinitionDto param : endpoint.getParameters()) {
                fields.add(convertParameterToSchemaField(param));
            }
        }
        
        // 2. Flatten requestBodySchema (top-level only)
        if (endpoint.getRequestBodySchema() != null) {
            fields.addAll(flattenTopLevelProperties(endpoint.getRequestBodySchema()));
        }
        
        return fields;
    }
    
    private SchemaFieldDto convertParameterToSchemaField(ParameterDefinitionDto param) {
        return SchemaFieldDto.builder()
            .name(param.getName())
            .type(inferTypeFromJsonSchema(param.getSchema()))
            .required(param.isRequired())
            .build();
    }
    
    private List<SchemaFieldDto> flattenTopLevelProperties(Map<String, Object> schema) {
        // Extract "properties" from JSON Schema
        // For each property, create SchemaFieldDto with appropriate type
        // Nested objects -> OBJECT type
        // Arrays -> ARRAY type
        // ...
    }
}
```

### Embedded `endpointRef` inside TestSuite (v1)

We embed `endpointRef` JSON directly into `test_suites` rather than introducing a separate EndpointContract resource now.

- **Structure**: Modeled after a subset of **OpenAPI 3.1 Operation Object**, but adapted for our storage needs (see `EndpointContractDto` above).
- **Library**: Use `io.swagger.parser.v3:swagger-parser` (v2.1.22+) for $ref resolution during import. DTOs remain clean POJOs.
- **Persistence**: Stored as `jsonb` only (no projection columns). Query by JSONB operators if needed.

### Use Postgres `jsonb` for schemas and datasets (JSONB-Only Approach)

**Decision: No projection columns. Store all JSON data as `jsonb` only.**

#### test_suites table columns:

| Column | Type | Description |
|--------|------|-------------|
| `id` | `VARCHAR(36)` | Primary key (UUID) |
| `name` | `VARCHAR(255)` | TestSuite name |
| `description` | `VARCHAR(2000)` | Optional description |
| `deployment_ref` | `jsonb` | DeploymentReferenceDto (id, name, version) |
| `endpoint_ref` | `jsonb` | EndpointContractDto (method, relativeUrl, schemas) |
| `test_cases_definition` | `jsonb` | TestCasesDefinitionDto (factFields only; parameterFields computed) |
| `version` | `BIGINT` | Optimistic locking version |
| `created_by` | `VARCHAR(255)` | Creator identifier |
| `created_at_ms` | `BIGINT` | Creation timestamp |
| `updated_at_ms` | `BIGINT` | Last update timestamp |

#### test_cases table columns:

| Column | Type | Description |
|--------|------|-------------|
| `id` | `VARCHAR(36)` | Primary key (UUID) |
| `test_suite_id` | `VARCHAR(36)` | Foreign key to test_suites |
| `test_case_name` | `VARCHAR(255)` | Display name |
| `parameters` | `jsonb` | Dynamic inputs (Map<String, Object>) |
| `facts` | `jsonb` | Dynamic ground truths (Map<String, Object>) |
| `is_enabled` | `BOOLEAN` | Default: true |
| `is_valid` | `BOOLEAN` | Schema validation status |
| `validation_warnings` | `TEXT[]` | Validation error messages (max configurable) |
| `created_at_ms` | `BIGINT` | Creation timestamp |
| `updated_at_ms` | `BIGINT` | Last update timestamp |

#### revalidation_tasks table columns:

| Column | Type | Description |
|--------|------|-------------|
| `id` | `VARCHAR(36)` | Primary key (UUID) |
| `test_suite_id` | `VARCHAR(36)` | Foreign key to test_suites |
| `status` | `VARCHAR(20)` | PENDING, RUNNING, COMPLETED, FAILED, TIMED_OUT |
| `total_cases` | `INTEGER` | Total TestCases to process |
| `processed_cases` | `INTEGER` | TestCases processed so far |
| `valid_count` | `INTEGER` | Count of valid TestCases |
| `invalid_count` | `INTEGER` | Count of invalid TestCases |
| `started_at_ms` | `BIGINT` | Job start timestamp |
| `completed_at_ms` | `BIGINT` | Job completion timestamp (null if not done) |
| `error_message` | `TEXT` | Error details if FAILED |

#### Indexing Strategy

| Index | Type | Rationale |
|-------|------|-----------|
| `idx_test_cases_test_suite_id` | B-tree | Fast lookup by parent suite |
| `idx_test_cases_parameters` | GIN | Future: JSON path filtering |
| `idx_test_cases_facts` | GIN | Future: JSON path filtering |
| `idx_test_suites_created_at_ms` | B-tree | Sorting/pagination |
| `idx_test_cases_created_at_ms` | B-tree | Sorting/pagination |
| `idx_revalidation_tasks_test_suite_id` | B-tree | Fast lookup by parent suite |

**Note**: Functional indexes on JSONB (e.g., `deployment_ref->>'id'`) can be added later if query patterns require them.

#### Rationale

- `jsonb` gives efficient storage, optional indexing, and flexible schema evolution
- No projection columns avoids data duplication and sync complexity
- PostgreSQL JSONB operators are efficient for expected query volumes
- Projection columns can be added later if performance testing shows need

### JDBC helper: `PostgresJsonbSqlParameter`

Introduce a small Postgres-specific helper used by repositories to bind JSON documents as Postgres `jsonb` (via `PGobject`), and to read back JSON as `String` for Jackson parsing.

**Naming rationale**: Prefixed with `Postgres` because this helper is specific to PostgreSQL's JSONB type. The system supports multiple database vendors, and other implementations may require different helpers.

Rationale: avoid scattered `::jsonb` casts and keep repository code consistent across entities.

### Pagination Infrastructure

#### Configuration

```yaml
pagination:
  default-size: ${PAGINATION_DEFAULT_SIZE:100}
  max-size: ${PAGINATION_MAX_SIZE:1000}
```

```java
@ConfigurationProperties(prefix = "pagination")
public class PaginationProperties {
    private int defaultSize = 100;
    private int maxSize = 1000;
}
```

#### Pagination Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `page` | int | 0 | Page number (0-based) |
| `size` | int | 100 | Page size (1-1000) |
| `includeTotalCount` | boolean | false | Include totalElements/totalPages in response |

#### Response Structure

```java
@Data
@Builder
public class PageResponseDto<T> {
    private List<T> content;
    private int page;                  // Current page (0-based)
    private int size;                  // Requested page size
    private Long totalElements;        // Total items (null if includeTotalCount=false)
    private Integer totalPages;        // Total pages (null if includeTotalCount=false)
}
```

**Note**: `totalElements` and `totalPages` are only populated when `includeTotalCount=true` to avoid expensive COUNT queries on large tables.

### Filtering Infrastructure

#### Filter DSL Format

`filter=<field>:<operator>:<value>`

- Multiple `filter` params are ANDed together
- Multiple filters on same field are allowed (e.g., range queries)
- Values must be URL-encoded (`:` as `%3A`, etc.)

#### Supported Operators (v1)

| Operator | Meaning | Applicable Types | SQL |
|----------|---------|------------------|-----|
| `eq` | Equals | String, Long, Boolean, UUID | `= ?` |
| `ne` | Not equals | String, Long, Boolean, UUID | `!= ?` or `<> ?` |
| `contains` | Substring match (case-insensitive) | String | `ILIKE '%' || ? || '%'` |
| `gt` | Greater than | Long (epoch ms) | `> ?` |
| `gte` | Greater than or equal | Long (epoch ms) | `>= ?` |
| `lt` | Less than | Long (epoch ms) | `< ?` |
| `lte` | Less than or equal | Long (epoch ms) | `<= ?` |

#### Case Sensitivity

- `eq`: **Case-sensitive** (exact match)
- `ne`: **Case-sensitive** (exact match)
- `contains`: **Case-insensitive** (uses PostgreSQL `ILIKE`)

#### FilterCondition Model

```java
@Data
@Builder
public class FilterCondition {
    private String field;              // API field name
    private FilterOperator operator;   // enum: EQ, NE, CONTAINS, GT, GTE, LT, LTE
    private String rawValue;           // Original string value from request
    private Object parsedValue;        // Type-coerced value for SQL binding
}

public enum FilterOperator {
    EQ, NE, CONTAINS, GT, GTE, LT, LTE
}
```

#### Filter Parsing & Validation

1. Parse `filter` param: split by `:` (first two `:` only)
2. Validate field against entity whitelist
3. Validate operator against field's allowed operators
4. Parse value to field's expected type
5. Return 400 Bad Request for any validation failure

#### Error Response Format

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Invalid filter: unknown field 'foo'",
  "details": {
    "filter": "foo:eq:bar",
    "reason": "Field 'foo' is not filterable"
  }
}
```

### Entity Filtering Whitelists

#### TestSuites Filtering Whitelist

| Field | Operators | Type | SQL Column |
|-------|-----------|------|------------|
| `name` | `eq`, `ne`, `contains` | String | `name` |
| `createdBy` | `eq`, `ne` | String | `created_by` |
| `createdAt` | `gt`, `gte`, `lt`, `lte` | Long (epoch ms) | `created_at_ms` |

#### TestCases Filtering Whitelist

| Field | Operators | Type | SQL Column |
|-------|-----------|------|------------|
| `testCaseName` | `eq`, `ne`, `contains` | String | `test_case_name` |
| `isEnabled` | `eq`, `ne` | Boolean | `is_enabled` |
| `isValid` | `eq`, `ne` | Boolean | `is_valid` |
| `createdAt` | `gt`, `gte`, `lt`, `lte` | Long (epoch ms) | `created_at_ms` |

**Future (v2)**: Filtering on `parameters.*` and `facts.*` JSONB paths.

### Default Sort Order

Each entity defines a default sort order used when no `sort` param is provided:

| Entity | Default Sort |
|--------|--------------|
| TestSuite | `createdAt,desc` |
| TestCase | `createdAt,desc` |
| MetricDefinition | `createdAt,desc` |

### Reusable Filtering & Sorting Infrastructure

Implementation consists of dedicated, injectable Spring components (not static utils) to ensure testability and mockability:

1. **FilterParser** (`@Component`): Parses repeatable `filter` param into `List<FilterCondition>`
2. **SortParser** (`@Component`): Parses repeatable `sort` param into `List<SortKey>` (per existing sorting spec)
3. **WhereBuilder** (`@Component`): Builds parameterized SQL WHERE clause from `List<FilterCondition>` + entity whitelist
4. **OrderByBuilder** (`@Component`): Builds safe SQL ORDER BY clause from `List<SortKey>` + entity whitelist

All list endpoints use this shared infrastructure to ensure consistent behavior and security.

Open question (future task): evolve the DSL (OR groups, nested jsonb-path filters, or adopting a standard such as RSQL/OData) once UI usage is clearer.

### CSV Import/Export Design

#### Library

Use **Apache Commons CSV** (`org.apache.commons:commons-csv`) for CSV parsing and generation:
- RFC 4180 compliant
- Streaming-friendly API for large files
- Configurable delimiters, quote chars, escape handling

#### CSV Format Specification

| Aspect | Value |
|--------|-------|
| Encoding | UTF-8 |
| Delimiter | Configurable (comma `,` default) |
| Quote character | Double-quote `"` |
| Escape | RFC 4180 (double the quote char) |
| Line ending | CRLF or LF (accept both on import) |

#### Configuration Properties

```yaml
csv:
  export:
    page-size: ${CSV_EXPORT_PAGE_SIZE:500}   # Page size for iterative DB fetches (capped by pagination max)
  import:
    max-file-size: ${CSV_IMPORT_MAX_FILE_SIZE:10MB}
    max-rows: ${CSV_IMPORT_MAX_ROWS:100000}
    batch-size: ${CSV_IMPORT_BATCH_SIZE:1000}
```

```java
@ConfigurationProperties(prefix = "csv.export")
public class CsvExportProperties {
    private int pageSize = 500;  // Page size for paginated export queries
}

@ConfigurationProperties(prefix = "csv.import")
public class CsvImportProperties {
    private DataSize maxFileSize = DataSize.ofMegabytes(10);
    private int maxRows = 100000;
    private int batchSize = 1000;  // Rows per batch insert
}
```

#### Export Endpoint

`GET /api/v1/test-suites/{testSuiteId}/test-cases/export.csv`

Export uses **paginated queries** (page size from `csv.export.page-size`, capped by pagination max) and **streams** the CSV to the response body; the full response is not built in memory.

| Aspect | Specification |
|--------|---------------|
| Query parameters | `delimiter` (char, default: `,`), `includeIsEnabled` (boolean, default: false), standard filter params |
| Response Content-Type | `text/csv; charset=UTF-8` |
| Response Content-Disposition | `attachment; filename="test-cases-{testSuiteId}.csv"` |
| Success response | `200 OK` with CSV body (streamed) |
| Empty suite | `200 OK` with header-only CSV (no data rows) |

**Column ordering**: `testCaseName`, then parameterFields (computed from endpointRef), then factFields, optionally `isEnabled`.

#### Import Preview Endpoint

`POST /api/v1/test-suites/{testSuiteId}/test-cases/import/preview`

| Aspect | Specification |
|--------|---------------|
| Content-Type | `multipart/form-data` |
| Form field for file | `file` |
| Query parameters | `delimiter` (char, default: `,`) |
| Success response | `200 OK` with `CsvImportPreviewDto` (detectedColumns, totalRows, sampleRows, warnings) |
| Error responses | `400 Bad Request` if CSV has no data rows or file is malformed |

Parses and validates the CSV without persisting. Use for dry-run / preview before actual import.

#### Import Endpoint

`POST /api/v1/test-suites/{testSuiteId}/test-cases/import`

| Aspect | Specification |
|--------|---------------|
| Content-Type | `multipart/form-data` |
| Form field for file | `file` |
| Query parameters | `delimiter` (char, default: `,`) |
| Success response | `200 OK` with `CsvImportResultDto` (totalRows, validCount, invalidCount, warnings) |
| Error responses | `400 Bad Request` if CSV has no data rows or file is malformed |

**Import Policy**: Replace-all (atomic transaction). All existing test cases in the suite are deleted and replaced with imported rows.

**Error Handling**: **Soft import** - invalid rows get `isValid=false` but are still persisted. Only malformed CSV (unparseable structure) returns 400.

#### Column Disambiguation (Parameter vs Fact)

**Hybrid approach**: Lookup-based matching with optional prefix support.

**Resolution order**:
1. If header has `param.` prefix → parameter field (strip prefix)
2. If header has `fact.` prefix → fact field (strip prefix)
3. If header matches a name in `parameterFields` → parameter field
4. Otherwise → fact field

**Example**:
```csv
testCaseName,param.query,expectedScore,fact.confidence
```
- `param.query` → parameter (explicit prefix)
- `expectedScore` → fact (no match in parameterFields, defaults to fact)
- `fact.confidence` → fact (explicit prefix)

#### testCaseName Column Handling

- If `testCaseName` column exists → use values from CSV
- If `testCaseName` column is missing → generate sequential names: "Row 1", "Row 2", etc.

#### isEnabled Column Handling

- Export: Include `isEnabled` column only if `includeIsEnabled=true` query param
- Import: If `isEnabled` column exists, use values; otherwise default to `true`

#### Import Response DTOs

```java
@Data
@Builder
public class CsvImportResultDto {
    private int totalRows;              // Total rows processed
    private int validCount;             // Rows with isValid=true
    private int invalidCount;           // Rows with isValid=false
    private List<CsvImportWarningDto> warnings;  // Validation issues per row
}

@Data
@Builder
public class CsvImportWarningDto {
    private int rowNumber;              // 1-based row number
    private String columnName;          // Column with issue (nullable for row-level errors)
    private String message;             // Human-readable error
}
```

#### Dry-Run Response DTO

```java
@Data
@Builder
public class CsvImportPreviewDto {
    private List<CsvColumnInfoDto> detectedColumns;  // Detected schema with mapping
    private int totalRows;
    private List<TestCaseResponseDto> sampleRows;    // First 10 parsed rows
    private List<CsvImportWarningDto> warnings;
}

@Data
@Builder
public class CsvColumnInfoDto {
    private String headerName;          // Original CSV header
    private String mappedTo;            // "parameter" or "fact"
    private String fieldName;           // Resolved field name (without prefix)
    private SchemaFieldType inferredType;
}
```

#### Empty CSV Handling

- CSV with only header row (no data rows) → **400 Bad Request**
- At least one data row is required for import

#### Streaming & Batch Processing

- **Export**: CSV is streamed to the client; the server uses paginated DB reads (configurable `csv.export.page-size`) and does not build the full response in memory.
- **Import**: CSV is parsed and processed in **batches** (batch size from `csv.import.batch-size`): read a batch of rows from CSV, validate and persist the batch, then discard and proceed to the next batch; the server does not load the entire CSV dataset into memory before persisting.
- **Preview**: Rows are streamed; only the first N sample rows (and warnings) are kept in memory for the preview response.
- Entire import operation wrapped in single atomic transaction; if connection drops mid-import → transaction rolls back, no partial state.

### TestCase partial update

Support `PATCH` for TestCase using **RFC 7396 JSON Merge Patch** on the TestCase document (limited to `displayName`, `isEnabled`, `parameters`, `facts`).

Rationale: UI needs to toggle/adjust a single property without resubmitting the full case payload.

### Schema Validation Strategy: "Soft Validation"

Implement a "Soft Validation" approach to support iterative authoring of complex test cases.

#### Core Behavior

- **`isValid` Flag**: Boolean column on `TestCase` entity (stored in DB).
- **`validationWarnings`**: Text array column on `TestCase` entity (stored in DB, max warnings configurable).
- **Validation Scope**: Both `parameters` AND `facts` are validated. If either fails → whole TestCase is `isValid=false`.

#### Validation Rules

| Condition | Result |
|-----------|--------|
| Missing required field | **Invalid** |
| Wrong type (e.g., string where integer expected) | **Invalid** |
| Extra fields not defined in schema | **Invalid** |
| Empty `parameters` or `facts` object `{}` | **Valid** (if schema has no required fields) |
| Empty object when schema has required fields | **Invalid** |
| Null value for optional field | **Valid** |

#### JSON Schema Configuration

| Aspect | Value |
|--------|-------|
| Draft version | **Draft-07** (widely adopted, good compatibility) |
| Library | `com.networknt:json-schema-validator` |
| Schema caching | Compile once per TestSuite, cache in memory, invalidate on schema change |

#### Validation Warnings

```yaml
validation:
  max-warnings-per-case: ${VALIDATION_MAX_WARNINGS:5}
```

- **Storage**: Persisted in `validation_warnings` column (text array)
- **Format**: Simple strings (e.g., `"Field 'temperature' expected number, got string"`)
- **Limit**: Configurable, default 5 warnings per TestCase
- **Response**: Only included when `includeWarnings=true` query param is set

```java
@ConfigurationProperties(prefix = "validation")
public class ValidationProperties {
    private int maxWarningsPerCase = 5;
}
```

#### On Save (Create/Update/Import)

1. Validate `parameters` against combined schema from `endpointRef.parameters` + `endpointRef.requestBodySchema`
2. Validate `facts` against `testCasesDefinition.factFields` schema
3. If all validations pass: `isValid = true`, `validationWarnings = []`
4. If any validation fails: `isValid = false`, `validationWarnings = [error messages]` (truncated to max)
5. **Always persist** the TestCase (soft validation - never reject due to validation failure)

#### On PATCH

- **Always recalculate** `isValid` after any PATCH operation (even if only `isEnabled` changed)
- This ensures consistency and simplifies implementation for v1

#### On Schema Change (Async Re-validation)

When `TestCasesDefinition.factFields` or `endpointRef` changes in a TestSuite:

```yaml
validation:
  revalidation:
    batch-size: ${VALIDATION_REVALIDATION_BATCH_SIZE:500}
    timeout-minutes: ${VALIDATION_REVALIDATION_TIMEOUT:5}
```

```java
@ConfigurationProperties(prefix = "validation.revalidation")
public class RevalidationProperties {
    private int batchSize = 500;
    private int timeoutMinutes = 5;
}
```

**Execution Model**:
1. API returns immediately with `202 Accepted` and a task ID
2. Background job processes TestCases in batches (configurable batch size)
3. Job has configurable timeout (default 5 minutes)
4. Client can poll task status endpoint to track progress

**Task Status Response**:
```java
@Data
@Builder
public class RevalidationTaskDto {
    private UUID taskId;
    private UUID testSuiteId;
    private RevalidationStatus status;  // PENDING, RUNNING, COMPLETED, FAILED, TIMED_OUT
    private int totalCases;
    private int processedCases;
    private int validCount;
    private int invalidCount;
    private Long startedAt;             // Epoch ms
    private Long completedAt;           // Epoch ms, null if not done
    private String errorMessage;        // Populated if FAILED
}

public enum RevalidationStatus {
    PENDING, RUNNING, COMPLETED, FAILED, TIMED_OUT
}
```

**Endpoints**:
- `PUT /api/v1/test-suites/{id}` → Returns `202 Accepted` with `RevalidationTaskDto` if schema changed
- `GET /api/v1/test-suites/{id}/revalidation-tasks/{taskId}` → Get task status
- `GET /api/v1/test-suites/{id}/revalidation-tasks` → List recent tasks for suite

#### TestSuite Schema Validation

When creating or updating a TestSuite, validate that embedded schemas are well-formed:

- `endpointRef.requestBodySchema` must be valid JSON Schema Draft-07
- `endpointRef.responseBodySchema` must be valid JSON Schema Draft-07
- `endpointRef.parameters[*].schema` must be valid JSON Schema Draft-07
- `testCasesDefinition.factFields` must be well-formed `SchemaFieldDto` list

**If schema is invalid**: Return `400 Bad Request` with error details. Do NOT persist the TestSuite.

### Concurrency & Transactional Integrity

#### Optimistic Locking

Use `version` column on `TestSuite` to prevent lost updates during concurrent edits.

**Operations that check version:**
- `PUT /api/v1/test-suites/{id}` - full replacement
- `PATCH /api/v1/test-suites/{id}` - partial update
- `POST /api/v1/test-suites/{id}/test-cases/import` - CSV import

**Client sends version via `If-Match` header:**
```http
PUT /api/v1/test-suites/123
If-Match: "5"
Content-Type: application/json

{ ... }
```

**Version mismatch response:**
```http
HTTP/1.1 409 Conflict
Content-Type: application/json

{
  "status": 409,
  "error": "Conflict",
  "message": "Resource was modified by another request",
  "code": "VERSION_CONFLICT"
}
```

**TestCase entities**: No individual version column. TestCases are protected by the parent TestSuite's version (especially important for CSV import replace-all operations).

**Version in response**: Include `version` field in TestSuite response DTOs and `ETag` header:
```http
HTTP/1.1 200 OK
ETag: "5"
Content-Type: application/json

{
  "id": "...",
  "version": 5,
  ...
}
```

#### Atomic CSV Import

The "delete all + insert all" operation for CSV import MUST be performed in a single atomic transaction with `ISOLATION LEVEL READ COMMITTED` to ensure the suite never ends up in an inconsistent state.

### Cascade Delete

When a TestSuite is deleted, all child entities are cascade deleted:

| Entity | Delete Method |
|--------|---------------|
| TestCases | DB CASCADE constraint |
| RevalidationTasks | DB CASCADE constraint |
| MetricBindings (future) | DB CASCADE constraint |
| Runs (future) | DB CASCADE constraint |

**Response format for TestSuite delete:**
```json
{
  "deleted": true,
  "deletedTestCases": 42,
  "deletedRevalidationTasks": 3
}
```

### TestSuite Field Mutability

| Field | Mutable | Notes |
|-------|---------|-------|
| `id` | ❌ No | Immutable primary key |
| `name` | ✅ Yes | |
| `description` | ✅ Yes | |
| `deploymentRef` | ✅ Yes | Allowed to change deployment binding |
| `endpointRef` | ✅ Yes | Triggers async re-validation of TestCases |
| `testCasesDefinition` | ✅ Yes | Triggers async re-validation of TestCases |
| `version` | Auto | Incremented by system |
| `createdBy` | ✅ Yes | Renamed to "maintainer" - can be reassigned |
| `createdAt` | ❌ No | Immutable creation timestamp |
| `updatedAt` | Auto | Updated by system |

### Error Response Standardization

All error responses follow a standard format with machine-readable error codes:

```java
@Data
@Builder
public class ErrorResponseDto {
    private int status;           // HTTP status code
    private String error;         // HTTP status text
    private String message;       // Human-readable summary
    private String code;          // Machine-readable error code
    private Map<String, Object> details;  // Context-specific details (optional)
}
```

#### Standard Error Codes

| Code | HTTP Status | Description |
|------|-------------|-------------|
| `VALIDATION_ERROR` | 400 | Request body validation failed |
| `INVALID_FILTER` | 400 | Invalid filter parameter |
| `INVALID_SORT` | 400 | Invalid sort parameter |
| `INVALID_SCHEMA` | 400 | JSON Schema is malformed |
| `CSV_PARSE_ERROR` | 400 | CSV file could not be parsed |
| `CSV_EMPTY` | 400 | CSV file has no data rows |
| `CSV_TOO_LARGE` | 400 | CSV exceeds size/row limits |
| `AUTHENTICATION_REQUIRED` | 401 | Missing or invalid JWT |
| `ACCESS_DENIED` | 403 | Insufficient permissions |
| `NOT_FOUND` | 404 | Resource not found |
| `VERSION_CONFLICT` | 409 | Optimistic locking version mismatch |
| `INTERNAL_ERROR` | 500 | Unexpected server error |

### createdBy Attribution

Configuration for extracting user identity from JWT:

```yaml
security:
  jwt:
    user-claim: ${SECURITY_JWT_USER_CLAIM:sub}
```

```java
@ConfigurationProperties(prefix = "security.jwt")
public class JwtSecurityProperties {
    private String userClaim = "sub";  // JWT claim for user identity
}
```

**Behavior by security mode:**

| Security Mode | JWT Missing | Claim Missing |
|---------------|-------------|---------------|
| `none` | Use "anonymous" | Use "anonymous" |
| `oidc` | 401 Unauthorized | 401 Unauthorized |

**Note**: `createdBy` is mutable and can be reassigned (e.g., transfer ownership to another maintainer).

## Risks / Trade-offs

- **Embedded endpointRef may cause duplication** → Mitigation: accept duplication in v1; split into a shared EndpointContract catalog later if needed.
- **Filtering DSL scope creep** → Mitigation: enforce a strict v1 spec (AND-only, whitelist), keep a follow-up task to revisit.
- **CSV schema detection ambiguity** → Mitigation: document deterministic rules (preferred column names, fallback heuristics), and provide strict mode later.
- **Large datasets** (CSV import size) → Mitigation: stream parse CSV, batch inserts, and set reasonable size limits (configurable later).
- **Concurrency in Bulk Operations** → Mitigation: Implement optimistic locking on TestSuite and atomic transactions for CSV replace-all operations.
- **Schema Evolution Mismatch** → Mitigation: Adopt "Schema-on-Write" policy; existing data is not validated until updated.

## Migration Plan

- Introduce new Flyway migration(s) creating required tables/columns with `jsonb`.
- Update controllers/services/repos to new DTO shapes.
- Update functional tests to match new behavior (backward compatibility not required).

### Metric Definitions Stub

Provide a minimal MetricDefinition entity for v1 to enable future metric binding:

#### MetricDefinition Fields (Minimal)

| Field | Type | Description |
|-------|------|-------------|
| `id` | UUID | Primary key |
| `name` | String | Metric display name |
| `description` | String | Optional description |
| `createdAt` | Long | Epoch milliseconds |

#### Seed Data

On application startup (or via migration), seed with sample metrics:
- "Accuracy" - Measures correctness of responses
- "Latency" - Measures response time in milliseconds
- "Relevance" - Measures relevance score

#### Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/v1/metric-definitions` | List all metrics (paginated, filtered, sorted) |
| `GET` | `/api/v1/metric-definitions/{id}` | Get metric by ID |

**Note**: Create/Update/Delete operations deferred to v2.

**Implementation**: Read-only `MetricDefinitionRepository` with pagination, filtering (whitelist: `name` eq/ne/contains, `createdAt` gt/gte/lt/lte), and sorting (default `createdAt,desc`). Table and seed data in Flyway migration `V1.2__TestSuiteAggregateTables.sql`. Functional tests cover list, get-by-ID, filtering, sorting, and 404.

## Resolved Questions (from spec review)

| Question | Resolution |
|----------|------------|
| Schema validation policy | Soft validation: persist with `isValid=false`, do not reject |
| $ref resolution | Always resolve on import; store self-contained schemas |
| Projection columns | JSONB-only; no projection columns in v1 |
| CSV type inference | Primitives only (STRING, INTEGER, NUMBER, BOOLEAN); OBJECT/ARRAY require schema |
| parameterFields storage | Computed at runtime from endpointRef; only factFields stored |
| Deployment URL | Not stored; use `dial.components.core.base-url` config |
| requestBody flattening | Top-level only; nested objects remain as OBJECT type |
| CSV import policy | Replace-all for v1 |
| CSV column disambiguation | Hybrid: lookup-based (parameterFields priority) + optional `param.`/`fact.` prefixes |
| CSV format | UTF-8, configurable delimiter (comma default), double-quote escaping |
| CSV testCaseName missing | Fallback to "Row 1", "Row 2", etc. |
| CSV isEnabled column | Optional: `includeIsEnabled` query param on export, import accepts if present |
| CSV export filtering | Supports standard filter query params |
| CSV empty file | Error (400) - at least one data row required |
| CSV error handling | Soft import: invalid rows get `isValid=false`, all rows persisted |
| CSV library | Apache Commons CSV (`org.apache.commons:commons-csv`) |
| Pagination page numbering | 0-based |
| Pagination default size | 100 |
| Pagination max size | 1000 |
| Pagination total count | Optional (`includeTotalCount=true`) |
| Filter operators v1 | eq, ne, contains, gt, gte, lt, lte |
| Filter value escaping | URL encoding only |
| Filter case sensitivity | `eq`/`ne` case-sensitive, `contains` case-insensitive |
| Default sort order | Per-entity, typically `createdAt,desc` |
| Multiple filters on same field | Allowed (enables range queries) |
| Validation scope | Both `parameters` AND `facts`; either failing → whole TestCase invalid |
| Validation rules | Invalid: missing required, wrong type, extra fields. Valid: empty objects (if no required), null optional |
| validationWarnings storage | Stored in DB column; simple strings; max configurable (default 5) |
| validationWarnings response | Only on explicit `includeWarnings=true` param; limited to max |
| Schema change re-validation | Async background job; configurable batch size and timeout (5 min default) |
| PATCH validation timing | Always recalculate `isValid` after any PATCH (v1) |
| Empty parameters/facts | Invalid if schema has required fields |
| JSON Schema draft version | Draft-07 (widely adopted) |
| Schema caching | Compile once per TestSuite, cache in memory, invalidate on change |
| Invalid schema handling | Prevent save of TestSuite (400 error) |
| Optimistic locking operations | PUT + PATCH + CSV Import check version |
| Version mismatch response | 409 Conflict, no version details in body |
| Version header | `If-Match` header for request, `ETag` for response |
| TestCase version | No own version; rely on TestSuite version |
| Cascade delete | DB CASCADE for TestCases, RevalidationTasks; return counts |
| Error codes | Standardized codes (VALIDATION_ERROR, VERSION_CONFLICT, etc.) |
| Metric stub content | Seed with sample data (Accuracy, Latency, Relevance) |
| Metric stub fields | id, name, description, createdAt |
| Metric stub filtering | Full filtering/sorting infrastructure |
| createdBy JWT claim | Configurable via `security.jwt.user-claim`, default: `sub` |
| createdBy mutability | Mutable (can reassign maintainer) |
| Missing JWT claim | Fallback "anonymous" in `none` mode; 401 in `oidc` mode |
| deploymentRef mutability | Mutable (allowed to change) |
| endpointRef mutability | Mutable (triggers async re-validation) |
| TestSuite immutable fields | Only `id` and `createdAt` |
| CSV library | Apache Commons CSV 1.12.0 (RFC 4180, streaming, configurable) |
| JSON Schema library | networknt json-schema-validator 1.5.4 (Draft-07, caching) |
| OpenAPI parser library | Swagger Parser 2.1.25 ($ref resolution on import) |

## Open Questions (deferred to v2)

- Format specifiers for STRING type (date, date-time, email, uri, uuid)
- Advanced filtering (OR groups, nested jsonb-path filters, full-text search)
- Functional indexes on specific JSONB paths (add based on performance testing)

