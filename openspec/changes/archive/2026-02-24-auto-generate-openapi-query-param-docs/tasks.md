## 1. Core: Description Generator and Customizer

- [x] 1.1 Create `QueryParamDescriptionGenerator` utility class in `.configuration` package with static methods: `generateFilterDescription(FilterSpec)`, `generateSortDescription(SortSpec)`, `generatePageDescription(int)`, `generateSizeDescription(int, int)`, `generateCursorDescription()`. Field type labels: STRING→`string`, LONG→`timestamp (epoch ms)` or `integer` (heuristic on field name), BOOLEAN→`boolean (true/false)`, UUID→`uuid`, JSONB_STRING→`jsonb string`. Filter description includes markdown table (Field, Type, Operators, Example), format docs, AND semantics, max 32 note. Sort description includes sortable field list, default sort, format docs, max 32 note.
- [x] 1.2 Create `OpenApiQueryParamCustomizer` implementing `OpenApiCustomizer` in `.configuration` package. Static registry maps paths to `(FilterSpec, SortSpec, paginationType)` tuples referencing `FilterWhitelists`/`SortWhitelists` constants. Inject `PaginationProperties` for defaults/limits. Iterate OpenAPI paths, match registry, overwrite `filter`/`sort`/`page`/`size`/`cursor` parameter descriptions using generator. Set `example` values on filter and sort parameters.
- [x] 1.3 Unit test `QueryParamDescriptionGenerator`: verify filter description contains field-operator table rows, type labels with hints, format/semantics docs. Verify sort description contains field list and default. Verify page/size descriptions contain configured values.
- [x] 1.4 Unit test `OpenApiQueryParamCustomizer`: build a minimal `OpenAPI` object with paths and parameters, run customizer, assert descriptions were overwritten for registered paths and left unchanged for unregistered paths.

## 2. Controller Annotation Updates

- [x] 2.1 `TestSuiteController`: strip filter/sort/page/size `@Parameter` descriptions to minimal fallbacks (`"Filter conditions"`, `"Sort keys"`, `"Page number"`, `"Page size"`). Enrich `includeTotalCount` description with default and behavior note.
- [x] 2.2 `TestCaseController`: strip filter/sort/page/size descriptions to minimal fallbacks. Enrich `includeTotalCount`, `includeWarnings` descriptions. Enrich CSV export params: `delimiter` (default `,`, single ASCII char), `includeEnabled` (default false, adds enabled column). Enrich CSV import/preview params: `delimiter`, `importMode` (list OVERRIDE/APPEND/MERGE with behavior), `conflictStrategy` (list FAIL/SKIP/OVERRIDE with behavior).
- [x] 2.3 `MetricDeclarationController`: strip filter/sort/page/size descriptions to minimal fallbacks. Enrich `includeTotalCount` description.
- [x] 2.4 `TestSuiteRunController`: strip filter/sort/page/size descriptions to minimal fallbacks. Enrich `includeTotalCount` description.
- [x] 2.5 `AnalyticsResultController`: strip filter/size/cursor descriptions to minimal fallbacks. Enrich `cursor` description (opaque, from `nextCursor` in previous response, omit for first page).

## 3. Enum Schema Annotations

- [x] 3.1 Add `@Schema(description = "...")` on `CsvImportMode` enum and its constants: OVERRIDE ("Delete all existing test cases, then insert all CSV rows"), APPEND ("Keep existing test cases, insert only new rows"), MERGE ("Keep existing test cases, insert new rows and add new schema fields").
- [x] 3.2 Add `@Schema(description = "...")` on `CsvConflictStrategy` enum and its constants: FAIL ("Abort with HTTP 409 on first name collision"), SKIP ("Silently skip colliding rows, first wins"), OVERRIDE ("Replace existing rows with colliding names, last wins").

## 4. Verification

- [x] 4.1 Run `./gradlew checkstyleMain checkstyleTest` — verify no violations.
- [x] 4.2 Run `./gradlew test` — verify all existing tests pass and new unit tests pass.
- [x] 4.3 Manual Swagger UI check: start application locally, open `/swagger-ui.html`, verify filter/sort/page/size params show rich generated descriptions with tables on at least one list endpoint. Verify enum descriptions appear. Verify examples pre-fill in "Try it out" mode.
