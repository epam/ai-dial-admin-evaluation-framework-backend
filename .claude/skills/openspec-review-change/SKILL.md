---
name: openspec-review-change
description: Review an OpenSpec change bundle for quality, consistency, and implementation readiness. Use when the user wants to analyze specs before implementation, find issues across artifacts, or assess readiness for the apply step. Similar to speckit.analyze but with iterative find-and-fix capability. Trigger for phrases like "review the spec", "check the change", "is this ready to implement", "analyze artifacts", "review before implementing".
license: MIT
compatibility: Requires openspec CLI.
metadata:
  author: custom
  version: "1.0"
---

Iterative review-and-fix cycle for an OpenSpec change bundle. Analyzes all artifacts (proposal, specs, design, tasks) for quality issues, cross-artifact consistency, and implementation readiness. Each review iteration uses a stateless subagent — no context leakage between cycles.

## Parameters

Parse from the user's input after `/opsx:review`:

| Parameter | Format | Default | Description |
|-----------|--------|---------|-------------|
| change name | positional | (infer or ask) | Which change to review |
| `--max-iterations` | integer | `5` | Max review-fix cycles before stopping |
| `--fix-mode` | `direct` \| `propose` | `direct` | Edit artifacts in-place or output proposed diffs only |

Examples:
- `/opsx:review` — infer change from context
- `/opsx:review add-type-hints` — specific change
- `/opsx:review add-type-hints --max-iterations 3` — limit cycles
- `/opsx:review add-type-hints --fix-mode propose` — propose without editing

## Architecture

```
Parent Orchestrator (this skill, maintains state)
├── Iteration 1:
│   ├── Review Subagent (stateless) ──→ structured findings
│   └── Fix Subagent   (stateless) ──→ applies/proposes fixes
├── Iteration 2:
│   ├── Review Subagent (fresh context, sees fixed files)
│   └── Fix Subagent
└── … up to --max-iterations …
    └── Final Report (all findings + fixes accumulated)
```

Subagents receive **no context from previous iterations** — they start fresh, reading the current on-disk state of artifacts. This prevents anchoring bias and ensures each review reflects reality.

---

## Step 1 — Resolve change name

Follow the same pattern as other opsx commands:
- If a name was passed as an argument, use it directly.
- Otherwise, infer from recent conversation context.
- If ambiguous, run `openspec list --json` and use **AskUserQuestion** to let the user choose.

Announce: `Reviewing change: **<name>** | max-iterations: <N> | fix-mode: <mode>`

## Step 2 — Load change metadata

```bash
openspec status --change "<name>" --json
```

Verify the change exists and note which artifact IDs have `status: "done"`. Gracefully skip review dimensions for missing artifacts (e.g., no design.md → skip design checks).

## Step 3 — Collect context file paths

```bash
openspec instructions apply --change "<name>" --json
```

From the JSON, collect:
- `changeDir` — absolute path to the change directory
- `contextFiles` — map of artifact id → absolute path (may include glob patterns)

Additionally, resolve delta spec paths and their corresponding baseline specs:
- Delta specs: `<changeDir>/specs/*/spec.md` (use Glob to list them)
- Baseline specs: for each delta spec at `<changeDir>/specs/<folder>/spec.md`, the baseline is `openspec/specs/<folder>/spec.md`

## Step 4 — Review-fix loop

Initialize:
- `iteration = 0`
- `all_findings = []` — accumulated findings across all iterations, each tagged with iteration number
- `all_fixes = []` — accumulated fix reports

**Repeat while `iteration < max_iterations`:**

### 4a — Spawn Review Subagent

Use the **Agent tool** to spawn a review subagent with a fully self-contained prompt. The subagent must NOT rely on any context from this conversation — everything it needs must be in the prompt.

**Construct the prompt as follows** (fill in `<…>` placeholders):

---
```
You are reviewing an OpenSpec change bundle for quality, consistency, and implementation readiness.

## Change: <name>
## Iteration: <N> of <max>

## Files to Read

Read ALL of these files before starting your analysis:

1. openspec/config.yaml  ← the project "constitution": architecture rules, coding conventions
2. <proposal path if exists>
3. <design path if exists>
4. <tasks path if exists>
5. Delta specs (read each):
<list each resolved delta spec path>
6. Corresponding baseline specs (read each, if file exists):
<list each baseline spec path — same folder names under openspec/specs/>

## Review Criteria

Evaluate against ALL applicable dimensions. Skip dimensions whose artifacts are absent.

### A. Cross-Artifact Consistency
- Every proposal **goal** maps to ≥1 spec requirement
- Every spec requirement is reflected in ≥1 design decision (if design exists)
- Every key design decision has ≥1 implementing task (if tasks exist)
- Every task traces back to a spec requirement (no orphaned tasks)
- Delta spec changes do NOT contradict unchanged requirements in baseline specs

### B. Spec Quality
- Requirements use `### Requirement: <name>` heading format
- Scenarios use `#### Scenario: <name>` with WHEN/THEN bullet format
- Requirements are testable and unambiguous (avoid vague "should support", "may handle")
- Error and edge-case scenarios are covered for any non-trivial requirement
- No contradictions between scenarios within or across specs
- ADDED/MODIFIED/REMOVED sections are properly scoped (not accidentally overwriting unchanged content)
- Implementation notes reference specific packages, classes, or file paths where applicable

### C. Design Quality (skip if no design.md)
- Aligns with architecture rules from config.yaml (JDBC-only, no JPA, layered architecture, dual datasource qualifiers)
- Component interactions documented (controller → service → repo → DB)
- Transaction boundaries specified (metaTransactionManager vs analyticsTransactionManager)
- Error handling strategy stated
- DB migration strategy specified if schema changes (meta vs analytics Flyway path)
- Approach is incremental and minimal — not over-engineered

### D. Task Quality (skip if no tasks.md)
- All spec requirements have ≥1 implementing task
- Tasks reference specific files, packages, or class names
- Cross-cutting tasks present where applicable:
  - Unit + functional tests (`@PostgresFunctionalTests`)
  - Flyway migrations (correct db/migration path: meta or analytics)
  - OpenAPI annotation/example updates
  - Docs updates (`docs/database-schema.md`, `docs/configuration.md`)
  - `openspec/specs/README.md` update (if new spec folders added)
  - `AGENTS.md` or `openspec/config.yaml` update (if new patterns or conventions introduced)
- Tasks are small and independently completable
- Task ordering is sensible (no circular dependencies)

### E. Proposal Quality (skip if no proposal.md)
- Problem statement is clear and motivating
- Goals are specific (not generic)
- Non-goals explicitly listed (scope boundaries)
- Impact analysis covers: API changes, DB changes, security, config, breaking changes
- Risks identified

## Severity Classification

For each issue, assign one level:
- **CRITICAL** — blocks implementation; will cause significant rework, architectural violations, or production bugs
- **MAJOR** — significant gap likely to cause issues during or after implementation
- **MINOR** — worth noting but not blocking
- **SUGGESTION** — optional improvement

## Required Output Format

Output EXACTLY this structure (use these headings verbatim):

## Review Findings: Iteration <N>

### Assessment: <READY|NEEDS_WORK|BLOCKED>

Use READY if zero CRITICAL and zero MAJOR issues.
Use BLOCKED if any CRITICAL issues exist.
Use NEEDS_WORK otherwise.

### Issues

#### CRITICAL
(list issues, or write "None")
- **[C1]** `<Dimension>`: <one-line title>
  - **Artifact**: `<filename>`
  - **Details**: <specific description — quote relevant text when helpful>
  - **Recommendation**: <concrete, actionable fix>

#### MAJOR
(list issues, or write "None")
- **[M1]** `<Dimension>`: <title>
  - **Artifact**: `<filename>`
  - **Details**: <description>
  - **Recommendation**: <fix>

#### MINOR
(list or write "None")
- **[m1]** <description> → <recommendation>

#### SUGGESTIONS
(list or write "None")
- **[S1]** <description>

### Readiness Summary
| Dimension | Status | Notes |
|-----------|--------|-------|
| Cross-Artifact Consistency | PASS/FAIL/N/A | |
| Spec Quality | PASS/FAIL/N/A | |
| Design Quality | PASS/FAIL/N/A | |
| Task Quality | PASS/FAIL/N/A | |
| Proposal Quality | PASS/FAIL/N/A | |
```
---

### 4b — Parse review results

From the subagent's response extract:
- **Assessment**: READY / NEEDS_WORK / BLOCKED
- **CRITICAL issues**: all `[C…]` items
- **MAJOR issues**: all `[M…]` items
- **All other findings**: MINOR + SUGGESTIONS

Append all findings to `all_findings`, tagged `{ iteration: N, severity: "...", id: "...", ... }`.

### 4c — Termination check

**If Assessment is READY** → break the loop, proceed to Final Report.

**If `iteration + 1 >= max_iterations`** → break the loop (max reached), proceed to Final Report.

### 4d — Spawn Fix Subagent (only when MAJOR+ issues remain)

Use the **Agent tool** to spawn a fix subagent. Again, fully self-contained — no assumed context.

**`direct` mode (default) — edit artifact files in-place:**

---
```
You are fixing issues found during an OpenSpec change review.

## Change: <name>
## Change Directory: <changeDir>

## Findings to Fix

Fix ALL of the following CRITICAL and MAJOR issues. Do NOT touch MINOR or SUGGESTION items.

<paste the full CRITICAL and MAJOR findings from the review subagent, with IDs>

## Instructions

1. Read each artifact file mentioned in the findings.
2. For each CRITICAL or MAJOR finding, apply the recommended fix by editing the file.
   - Make targeted, minimal edits — fix only what is identified.
   - When the recommendation is ambiguous, use your best judgment and note it.
   - Prioritize CRITICAL over MAJOR if time is short.
3. Do NOT change MINOR or SUGGESTION items.
4. Do NOT restructure or rewrite sections not involved in a finding.

## Required Output Format

## Fixes Applied

### Fix <ID>: <finding title>
- **File edited**: `<path>`
- **Change**: <brief description of what was changed>
- **Finding addressed**: `<finding ID>`

### Unfixed Items
- **<ID>**: <reason it was not fixed — e.g., requires human judgment, contradictory recommendations>
```
---

**`propose` mode — output proposed changes without editing files:**

Same prompt as above, but replace the Instructions section with:

---
```
## Instructions

Do NOT edit any files. Instead, for each CRITICAL and MAJOR finding, output the proposed fix as:

### Proposed Fix <ID>: <finding title>
- **File**: `<path>`
- **Section/context**: <quote the current text that needs changing>
- **Proposed replacement**:
  <the new text>
- **Rationale**: <why this fixes the issue>
```
---

Append the fix subagent's full response to `all_fixes`, tagged with `{ iteration: N }`.

Increment `iteration` and continue the loop.

---

## Step 5 — Final Report

After the loop ends, present the consolidated report:

```
## Review Report: <change-name>

**Parameters**: max-iterations=<N>, fix-mode=<mode>
**Iterations used**: <actual>/<max>
**Final Assessment**: <READY | NEEDS_WORK | BLOCKED>

---

### Iteration Summary

| # | Assessment | CRITICAL | MAJOR | MINOR | Fixes Applied |
|---|-----------|----------|-------|-------|---------------|
| 1 | …         | …        | …     | …     | …             |

---

### All Findings (across all iterations)

<Group by severity: CRITICAL → MAJOR → MINOR → SUGGESTIONS>
<For each finding: show iteration it was found in, whether it was fixed, and the fix reference>

---

### Remaining Issues

<Any CRITICAL or MAJOR issues that were NOT fixed (unfixed items from fix subagents, or issues found in the last review iteration)>

---

### Recommendation

- **READY**: "No blocking issues. Run `/opsx:apply` to start implementation."
- **NEEDS_WORK** (max iterations reached): "<N> issue(s) remain after <max> iterations. Review remaining issues above — manual edits needed before implementing."
- **BLOCKED**: "Critical issues remain. Do not implement until resolved."
```

---

## Guardrails

- **Always run at least one review** — never skip directly to the final report.
- **Subagents are stateless** — include all necessary file paths and findings text in each subagent prompt; never assume inherited context.
- **Respect fix_mode** — in `propose` mode, subagents must NOT edit any files; the orchestrator must enforce this.
- **Severity threshold for fixes** — only CRITICAL and MAJOR trigger the fix subagent; MINOR and SUGGESTIONS are reported but never auto-fixed.
- **Graceful degradation** — if an artifact is missing, skip its review dimension and note the skip in the report.
- **Conservative fixes** — fix subagents make targeted edits only; when a fix would be ambiguous or risky, they report it as "unfixed" rather than guessing wrong.
- **Transparency** — even when fixes are applied in `direct` mode, all findings appear in the final report so nothing is silently buried.

## Workflow Position

```
/opsx:ff  or  /opsx:continue   →  artifacts created
/opsx:review                   →  review & fix cycle  ← THIS SKILL
/opsx:apply                    →  implementation
/opsx:verify                   →  verify implementation
/opsx:archive                  →  archive change
```
