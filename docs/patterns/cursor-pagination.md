# CursorCodec and Keyset Pagination

Used by the analytics layer. Cursor-based pagination avoids OFFSET/LIMIT problems for large result sets.

- **`Cursor`** record (`data.db.analytics.model.cursor`) — pure data carrier with `createdAt` (Long) and `id` (UUID). No encoding logic.
- **`CursorPage<T>`** record (`data.db.analytics.model.cursor`) — `content`, `nextCursor` (raw `Cursor`, nullable), `hasMore`.
- **`CursorCodec`** (`service.domain.analytics`) — `@Component` that encodes/decodes `Cursor` ↔ opaque URL-safe Base64 string. Inject `ObjectMapper`. Encoding is a service-layer concern; the data layer works with raw `Cursor` objects only.
- **`CursorPageResponseDto<T>`** (`service.domain.dto.analytics`) — `content`, `size` (requested page size), `nextCursor` (encoded String, nullable), `hasMore`.

Repository `findAll` pattern: query `LIMIT size + 1`; if `size + 1` rows returned, set `hasMore = true` and build `Cursor` from the last included item; otherwise `hasMore = false`, `nextCursor = null`. ORDER BY the composite PK descending (e.g., `created_at_ms DESC, id DESC`).

This keyset ordering is also why native partitioning on `created_at_ms` (see [Analytics Time-Based Partitioning](analytics-time-partitioning.md)) is transparent to pagination: the sort key and the partition key are the same column, so Postgres can walk partitions in the same descending order the cursor already assumes, and a page boundary that happens to fall on a partition boundary produces no different result than one that doesn't. Whenever the caller already knows the specific run's `created_at_ms`, supply it as an equality filter (`runCreatedAtMs`) alongside the cursor condition — this lets the query prune to a single partition instead of scanning every one.
