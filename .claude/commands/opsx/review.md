---
name: "OPSX: Review"
description: Review change artifacts for quality, consistency, and readiness before implementation
category: Workflow
tags: [workflow, review, analyze]
---

Review an OpenSpec change bundle for quality, consistency, and implementation readiness. Iterative review-and-fix cycle using stateless subagents — similar to speckit.analyze but with automatic fixing.

**Input**: Optionally specify a change name and parameters after `/opsx:review`.

Examples:
- `/opsx:review` — infer change from context or prompt to select
- `/opsx:review add-type-hints` — review a specific change
- `/opsx:review add-type-hints --max-iterations 3` — limit review-fix cycles
- `/opsx:review add-type-hints --fix-mode propose` — propose fixes without editing files

**Parameters**:
- Change name (positional, optional — infer from context if omitted)
- `--max-iterations N` (default: `5`) — max review-fix cycles before stopping
- `--fix-mode direct|propose` (default: `direct`) — edit artifacts in-place or output proposed diffs only

**Steps**

1. **Resolve change name**

   If a name is provided, use it. Otherwise:
   - Infer from conversation context if the user recently mentioned a change
   - Auto-select if only one active change exists
   - If ambiguous, run `openspec list --json` and use the **AskUserQuestion tool** to let the user select

   Announce: `Reviewing change: **<name>** | max-iterations: <N> | fix-mode: <mode>`

2. **Load change metadata**

   ```bash
   openspec status --change "<name>" --json
   ```

   Verify the change exists. Note which artifacts are present (`status: "done"`).

3. **Collect context file paths**

   ```bash
   openspec instructions apply --change "<name>" --json
   ```

   From the JSON, collect `changeDir` and `contextFiles`. Use Glob to find delta specs at `<changeDir>/specs/*/spec.md` and map them to baseline specs at `openspec/specs/<folder>/spec.md`.

4. **Run the review-fix loop** (up to `--max-iterations`, default 5)

   For each iteration:

   a. **Spawn Review Subagent** (Agent tool, fully self-contained prompt):
      - Reads: `openspec/config.yaml` + all change artifacts + delta specs + baseline specs
      - Evaluates: cross-artifact consistency, spec quality, design quality, task quality, proposal quality
      - Returns: structured findings with CRITICAL / MAJOR / MINOR / SUGGESTION severity, plus READY / NEEDS_WORK / BLOCKED assessment

   b. **If Assessment is READY** → break loop

   c. **If max iterations reached** → break loop

   d. **Spawn Fix Subagent** (Agent tool, fully self-contained prompt):
      - `direct` mode: edits artifact files in-place for all CRITICAL + MAJOR findings
      - `propose` mode: outputs proposed text changes without editing any files
      - MINOR and SUGGESTION items are never auto-fixed

   Accumulate all findings and fix reports across iterations.

5. **Compile and present Final Report**

   Show:
   - Iteration summary table (assessment + issue counts + fixes per iteration)
   - All findings across iterations (grouped by severity, with fix status)
   - Remaining unresolved issues (if any)
   - Final recommendation: ready for `/opsx:apply`, needs manual attention, or blocked

**Review Dimensions**

| Dimension | What is checked |
|-----------|----------------|
| Cross-Artifact Consistency | Goals → requirements → design → tasks traceability; delta vs baseline spec compatibility |
| Spec Quality | WHEN/THEN format, testable requirements, edge/error cases, no contradictions |
| Design Quality | Architecture alignment (JDBC, dual datasource, layers), transaction boundaries, migration strategy |
| Task Quality | Full requirement coverage, cross-cutting tasks (tests, migrations, docs, OpenAPI), file references |
| Proposal Quality | Clear goals, non-goals, impact analysis, risks |

**Guardrails**
- Subagents are stateless — each gets a fully self-contained prompt with all needed file paths
- Only CRITICAL and MAJOR issues trigger the fix cycle; MINOR and SUGGESTIONS are reported only
- In `propose` mode, no files are ever edited
- All findings appear in the final report even when auto-fixed
- Missing artifacts are gracefully skipped with a note in the report
