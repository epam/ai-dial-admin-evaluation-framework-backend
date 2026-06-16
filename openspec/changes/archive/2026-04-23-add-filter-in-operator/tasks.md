## 1. Core Model and Parser

- [x] 1.1 Add `IN` constant to `FilterOperator` enum (`data.db.model.filter.FilterOperator`)
- [x] 1.2 Update `FilterParser.parseSingle()` to handle `IN`: split raw value on `,`, URL-decode each element, reject blank list or blank elements with `FilterValidationException` (HTTP 400)
- [x] 1.3 Store the decoded list in `FilterCondition.parsedValue` as `List<String>` when operator is `IN`

## 2. SQL Generation

- [x] 2.1 Update `WhereBuilder.validateOperator()` to reject `IN` on `BOOLEAN`, `LONG`, and `JSONB_*` field types (HTTP 400)
- [x] 2.2 Add `IN` branch to `WhereBuilder.buildPredicate()`: generate `column IN (:paramN)` and bind `List<String>` as collection param
- [x] 2.3 Add UUID-element validation in `WhereBuilder.parseValue()` (or in `FilterParser`) for `IN` on UUID-typed fields: parse each element via `UUID.fromString`, throw `InvalidFilterException` on failure

## 3. Filter Whitelists

- [x] 3.1 Add `FilterOperator.IN` to the `testCaseName` entry in `FilterWhitelists.TEST_CASES`
- [x] 3.2 Add `FilterOperator.IN` to all `STRING` field entries in `FilterWhitelists.TEST_SUITES` (`name`, `createdBy`)
- [x] 3.3 Add `FilterOperator.IN` to all `STRING` field entries in `FilterWhitelists.TEST_SUITE_RUNS` (`status`, `testRunName`)
- [x] 3.4 Add `FilterOperator.IN` to all `STRING` and `UUID` field entries in `FilterWhitelists.EVAL_SUMMARIES` (`testCaseName`, `executionStatus`, `testCaseId`, `suiteId`, `runId`)
- [x] 3.5 Add `FilterOperator.IN` to all `STRING` and `UUID` field entries in `FilterWhitelists.ANALYTICS_RESULTS` (`testCaseName`, `executionStatus`, `testCaseId`, `suiteId`, `runId`)
- [x] 3.6 Add `FilterOperator.IN` to all `STRING` field entries in `FilterWhitelists.METRIC_DECLARATIONS` (`name`, `providerId`)

## 4. Unit Tests

- [x] 4.1 Extend `FilterParserTest`: `in` with two values parses correctly; single value; empty value → exception; blank element → exception; `%2C`-encoded comma treated as literal in element
- [x] 4.2 Extend `WhereBuilderTest` (or equivalent): `IN` on STRING generates correct `IN (:param)` SQL with list binding; `IN` on UUID field validates each element; `IN` on BOOLEAN field → exception; `IN` on LONG field → exception

## 5. Functional Tests

- [x] 5.1 Add functional test to `TestCaseController` test class: bulk delete by two names via `filter=testCaseName:in:name1,name2` deletes exactly those two test cases and returns `{"deleted": 2}`
- [x] 5.2 Add functional test: `filter=testCaseName:in:name1` (single value) deletes one test case
- [x] 5.3 Add functional test: `filter=testCaseName:in:` (empty value) returns HTTP 400

## 6. Spec Sync

- [x] 6.1 Update `openspec/specs/entity-filtering/spec.md` per delta spec (sync `in` operator into the main spec's "Structured filtering" requirement)
- [x] 6.2 Update `openspec/specs/README.md` per Spec Index Maintenance Policy if the `entity-filtering` summary is now inaccurate (check: summary should reflect `in` operator support)
