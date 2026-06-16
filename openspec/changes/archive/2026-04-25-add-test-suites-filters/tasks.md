## 1. TestSuites Filter Whitelist

- [x] 1.1 Add `id` entry to `FilterWhitelists.TEST_SUITES`: column `"id"`, type `STRING`, operators `{EQ}` (done: `filter=id:eq:<uuid>` accepted; other operators for id rejected with HTTP 400)
- [x] 1.2 Add `description` entry: column `"description"`, type `STRING`, operators `{CO}` (done: `filter=description:co:<text>` accepted)
- [x] 1.3 Add `updatedAt` entry: column `"updated_at_ms"`, type `LONG`, operators `{GT, GTE, LT, LTE}` (done: `filter=updatedAt:gte:<epoch>` accepted; `co` rejected)

## 1.1 Extend id filter

- [x] 1.4 Add `IN` operator to `id` entry: `EnumSet.of(EQ, IN)` (done: `filter=id:in:<uuid1>,<uuid2>` accepted)

## 2. Functional Tests (TestSuites List)

- [x] 2.1 Add functional test scenarios to the TestSuites functional test class covering:
  - `filter=id:eq:<uuid>` returns only the matching suite
  - `filter=description:co:<text>` returns only suites with matching description substring (case-insensitive)
  - `filter=updatedAt:gte:<epoch>` returns only suites updated at or after the epoch
  - `filter=updatedAt:lt:<epoch>` returns only suites updated before the epoch
  - `filter=id:in:<uuid1>,<uuid2>` returns only suites whose id is in the set
  (done: all scenarios pass via `./gradlew test --tests "*.TestSuiteFunctionalTests*"` or equivalent)
