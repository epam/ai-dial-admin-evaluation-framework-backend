## Context

See `proposal.md` for motivation and the delta spec for scope/matrices. Structural JSON, JSONata preprocessing, MCP full-value arguments, and multipart values share `ResourceRefPlaceholderResolver`. Generic variable resolution intentionally preserves empty strings; JSONata filtering occurs after resource conversion.

## Goals / Non-Goals

**Goal:** implement the spec's FILE-only fallback at the existing conversion seam, without duplicating FILE classification.

**Non-goals:** change generic variable resolution, interpolation, metadata, imports/persistence, or multipart serialization.

## Decisions

### Shared seam, caller-specific defaults

Extend `ResourceRefPlaceholderResolver` with default context. Reuse the existing FILE detector, apply blank/default selection only when the resolved String is blank and no explicit non-null constant won, then convert the selected value normally.

Flow: existing value selection → shared FILE classification → eligible blank fallback → existing conversion → evaluation/transport.

Pass default context from `TemplateContentResolver`, `JsonataSourcePreprocessor`, and the full-value MCP path. Preserve each caller's parsing semantics: HTTP default text remains String; MCP numeric/boolean defaults retain their parsed types and bypass String conversion. Parse defaults only when selected. Keep `TemplateVariableResolver`, MCP direct-name lookup, and existing missing/null branches unchanged. No new package/component or generic fallback framework is needed.

Rejected: import normalization leaves existing rows broken; generic empty-value fallback changes STRING semantics; accepting blank refs globally bypasses validation.

### Multipart eligibility follows conversion, not transport

`RequestResolver.resolveMultipartBody` routes both TEXT and FILE values through structural resolution. TEXT parts receive the schema; FILE parts omit it but still permit explicit `|file` conversion. The shared fallback therefore includes TEXT explicit/inferred FILE placeholders and FILE-part explicit `|file` placeholders. Add no transport-specific eligibility flag.

Hintless FILE-part values remain raw refs with no new fallback. Explicit DIAL-formatted refs can already be rejected by the FILE-part serializer; this separate incompatibility remains out of scope.

### Compatibility and verification

Explicit non-null constants remain authoritative, including synthetic constants used by variables-mode try-it-out. Data-backed preview/runs get the corrected fallback; explicit blank variable overrides remain unchanged.

Test the spec matrices at the seam and through structural/JSONata/MCP/multipart callers; retain the sentinel-filter regression. Add one data-backed `TryItOutFunctionalTests` regression using existing fixtures/mocked invocation to prove persisted blank data, schema wiring, and context startup. Execute targeted runner and functional suites before completing tasks.

No API/data model, transaction, configuration, dependency, or security changes. No architecture/config/index maintenance triggers apply.

## Risks / Trade-offs

- [Blank FILE data with defaults changes behavior] → bounded eligibility and compatibility matrices.
- [Invalid/nonexistent default or multipart incompatibility] → preserve existing validation/transport errors; no omission or existence guarantees.
- [HTTP/MCP parsing drift] → test typed MCP defaults without unifying the engines.

## Migration Plan

Normal binary deployment/rollback; no data rewrite. Future runs use the correction, historical results remain unchanged.
