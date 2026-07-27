---
name: fe-api-handoff
description: Use when the user wants to hand off the current branch's changes to a frontend developer/agent — "summarize changes for the FE", "write FE handoff", "what API changes does the frontend need". Works for a raw POC branch or one or more implemented opsx changes. Diffs the current branch against `development`, and writes a single raw-HTML instruction file (wrapped in one `<important>` block, no styling) to `/tmp/fe-api-handoff/` containing the overall context plus every API contract change (endpoints, request/response DTOs, status codes & errors, enums/constants) the frontend must support. Never makes UI/UX judgments. Chat gets brief highlights + the file path only.
allowed-tools: Read Grep Glob LSP Bash(git diff:*) Bash(git log:*) Bash(git show:*) Bash(git rev-parse:*) Bash(git merge-base:*) Bash(git rev-list:*) Bash(mkdir -p /tmp/fe-api-handoff) Write(/tmp/fe-api-handoff/*)
model: opus
effort: high
context: fork
agent: general-purpose
---

# Frontend API handoff

Produce one instruction file that lets a frontend developer/agent implement support for this branch's backend changes **without reading the backend diff**. The file is the deliverable; the chat gets only a short recap and the path.

You run in a forked, isolated context. Read and diff freely — only your final short summary reaches the main conversation, and the file write lands at `/tmp/fe-api-handoff/<branch>.html`.

## Scope — API facts only

Every statement in the file is a fact about **what the backend now accepts or returns**. You describe the contract; you do not tell the frontend how to build against it.

**In scope:** request/response field names, types, nullability, defaults; endpoint paths + HTTP methods; validation rules the server enforces; HTTP status codes + error `code` strings; new/changed enum values and exposed constants; the overall backend behavior a FE dev needs as context.

**Out of scope — never write these:** anything about rendering, forms, buttons, disabling controls, showing warnings, layout, grouping/sorting *for display*, or "the UI should…". No UI/UX design judgment of any kind.

**Conversion rule** — when you catch yourself writing frontend behavior, restate it as the underlying API fact and drop the rest:
- ✗ "The UI should show an inline validation error on the condition field."
  → ✓ "`POST`/`PUT` reject a malformed `condition` with `400 VALIDATION_ERROR`."
- ✗ "The UI must always resend `multiTurnId` since PUT is a full replacement."
  → ✓ "`PUT` is a full replacement: `multiTurnId`/`turnIndex` omitted from the body are cleared."
- ✗ "Group by `(traceId, runIndex)` to render a multi-turn."
  → ✓ "All turns of one multi-turn run share a `traceId`; each turn is a separate row with its own `turnIndex`."

The recipe below has no slot for UI/UX prose. If a sentence doesn't fit a slot, it doesn't belong.

## Steps

1. **Find the changes.** `git diff --stat development...HEAD` for the map, then `git diff development...HEAD` on the surfaces that define the contract:
   - `web.controller` / `experimental.query.web` — routes, methods, params, status codes
   - `service.domain.dto`, `service.domain.dto.analytics` — request/response DTO fields, `@Schema`, validation annotations, nullability
   - `constants`, enums — exposed constant/enum values
   - `web.handler` + `service.domain.exception` — error `code` strings and their HTTP status
   - `src/main/resources/openapi/examples/**` — concrete request/response shapes
2. **Get context, but derive facts from code.** For an opsx branch, read the change proposal(s) under `openspec/changes/*/proposal.md` and relevant `AGENTS.md` inline conventions for the *why*. Always confirm each contract fact against the actual DTO/controller/example — prose can lag the code.
3. **Write the file** to `/tmp/fe-api-handoff/<branch>.html` (sanitize `/` → `-` in the branch name; `mkdir -p /tmp/fe-api-handoff` first) using the template below.
4. **Return to chat**: 3–8 bullet highlights of the biggest contract changes + the absolute file path. Do NOT paste the file contents.

## Output template

One `<important>` block wrapping raw semantic HTML. **No** `style=`, `<style>`, `class=`, CSS, or markdown — structure only.

```html
<important>
<h1>Frontend API handoff — <branch> (vs development)</h1>

<h2>Overall context</h2>
<p>What this change does at the backend level and why it exists — 1–3 short paragraphs. Enough for a FE dev to understand the feature. Facts only, no UI recommendations.</p>

<h2>API contract changes</h2>

<h3>[NEW|CHANGED|REMOVED] METHOD /api/v1/path/{param}</h3>
<p>One line: what this endpoint does and what changed.</p>
<h4>Request</h4>
<ul>
  <li><code>fieldName</code> — type, nullable?, default, constraint (e.g. "String, max 2000, nullable"). Mark NEW/CHANGED/REMOVED.</li>
</ul>
<h4>Response (200/201)</h4>
<ul>
  <li><code>fieldName</code> — type, nullable?, when present/omitted. Mark NEW/CHANGED/REMOVED.</li>
</ul>
<h4>Status codes &amp; errors</h4>
<ul>
  <li><code>409 CONFLICT</code> — condition that triggers it.</li>
</ul>

<!-- repeat one <h3> block per changed endpoint -->

<h2>Enums &amp; constants</h2>
<ul>
  <li><code>NAME</code> = value — where it surfaces in the API.</li>
</ul>

<h2>Error-code reference</h2>
<table>
  <tr><th>Scenario</th><th>HTTP</th><th>code</th></tr>
  <tr><td>...</td><td>400</td><td>VALIDATION_ERROR</td></tr>
</table>
</important>
```

Omit a section only if this branch genuinely has nothing for it (e.g. no enum changes). Every changed endpoint gets its own `<h3>` block; do not collapse several endpoints into prose.

## Red flags — stop and fix before writing

- A sentence containing "UI", "user", "display", "render", "form", "button", "should show" → convert to an API fact or delete.
- You're about to print the HTML into the chat instead of the file → write the file; chat gets highlights + path only.
- Output is markdown, or HTML with `style`/`class`/CSS → strip to raw semantic tags inside one `<important>` block.
- A contract fact taken from a spec/proposal you didn't confirm against a DTO/controller/example → verify it.
