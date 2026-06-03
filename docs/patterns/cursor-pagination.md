# CursorCodec and Keyset Pagination

Used by the analytics layer. Cursor-based pagination avoids OFFSET/LIMIT problems for large result sets.

- **`Cursor`** record (`data.db.analytics.model.cursor`) — pure data carrier with `createdAt` (Long) and `id` (UUID). No encoding logic.
- **`CursorPage<T>`** record (`data.db.analytics.model.cursor`) — `content`, `nextCursor` (raw `Cursor`, nullable), `hasMore`.
- **`CursorCodec`** (`service.domain.analytics`) — `@Component` that encodes/decodes `Cursor` ↔ opaque URL-safe Base64 string. Inject `ObjectMapper`. Encoding is a service-layer concern; the data layer works with raw `Cursor` objects only.
- **`CursorPageResponseDto<T>`** (`service.domain.dto.analytics`) — `content`, `size` (requested page size), `nextCursor` (encoded String, nullable), `hasMore`.

Repository `findAll` pattern: query `LIMIT size + 1`; if `size + 1` rows returned, set `hasMore = true` and build `Cursor` from the last included item; otherwise `hasMore = false`, `nextCursor = null`. ORDER BY the composite PK descending (e.g., `created_at_ms DESC, id DESC`).
