## 1. Error code enums

- [x] 1.1 Add `UPSTREAM_AUTH_ERROR` and `UPSTREAM_NOT_FOUND` to `DialCoreErrorCode` enum (client package)
- [x] 1.2 Add `UPSTREAM_AUTH_ERROR` and `UPSTREAM_NOT_FOUND` to `ErrorCode` enum (web handler package)

## 2. Error mapping

- [x] 2.1 Update `DialCoreErrorMapper.toHttpStatus()`: map 401 → `HttpStatus.BAD_GATEWAY`, 404 → `HttpStatus.BAD_GATEWAY`
- [x] 2.2 Update `DialCoreErrorMapper.toDialCoreErrorCode()`: map 401 → `UPSTREAM_AUTH_ERROR`, 404 → `UPSTREAM_NOT_FOUND`
- [x] 2.3 Update `DefaultExceptionHandler.toErrorCode()` switch to map `DialCoreErrorCode.UPSTREAM_AUTH_ERROR` and `DialCoreErrorCode.UPSTREAM_NOT_FOUND` to corresponding `ErrorCode` values

## 3. API documentation and tests

- [x] 3.1 Update `DeploymentController` OpenAPI `@ApiResponse`: document 502 for upstream auth/not-found instead of 401/404 from Core
- [x] 3.2 Update `DialCoreErrorMapperTest`: assert 401 → 502 + UPSTREAM_AUTH_ERROR, 404 → 502 + UPSTREAM_NOT_FOUND; remove or adjust assertions that expected 401/404 pass-through
- [x] 3.3 Update deployment functional tests: when Core stub returns 401 or 404, assert response is 502 with `UPSTREAM_AUTH_ERROR` or `UPSTREAM_NOT_FOUND` in body
