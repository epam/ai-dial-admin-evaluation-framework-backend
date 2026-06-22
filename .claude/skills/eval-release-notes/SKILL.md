---
name: eval-release-notes
description: Use when the user asks to enhance, refine, polish, or "look at" the release notes for a tag — typically a fresh CI-generated pre-release (e.g. `0.8.0-rc.1`) or a stable cut. Reads the auto-generated notes off the GitHub release, classifies and rewrites each bullet in this project's editorial voice, builds the `Deployment Changes` section from `README.md` / `docs/configuration.md` / PR bodies, and saves a draft to `claude/release-notes/`. When the target is a stable cut (e.g. `0.8.0`) and its `-rc.*` pre-releases already carry enhanced notes, it assembles the stable draft by merging those rc notes — reusing their approved wording — instead of re-deriving every bullet from the raw stable notes. Never edits GitHub directly.
allowed-tools: Read Grep Glob LSP Bash(gh release view:*) Bash(gh release list:*) Bash(gh pr view:*) Bash(gh pr list:*) Bash(gh pr diff:*) Bash(git log:*) Bash(git show:*) Bash(git diff:*) Bash(git tag:*) Bash(git rev-parse:*) Bash(date:*) Write(claude/release-notes/*) Bash(mkdir -p claude/release-notes)
argument-hint: "[tag]"
arguments: tag
model: opus
effort: xhigh
context: fork
agent: general-purpose
---

# Evaluation Framework release-notes enhancer

The CI publishes a release for every tag with bullets that are just the PR titles. Those bullets carry a lot of dirt — conventional-commit prefixes (`feat:`, `fix(area):`), branch slugs (`async-dial-instead-of-dial-core-client`), misfiled items (a `feat(metric-providers)` landing under `Other` because the title started with `feat(`), and a `## Other` section that mixes consumer-relevant items with pure internal refactors. This skill applies a human editorial pass over those raw notes: classify each bullet by real user impact, rewrite it in terse house style, and assemble an operator-facing `Deployment Changes` section. The releases visible at `https://github.com/epam/ai-dial-admin-evaluation-framework-backend/releases` are the corpus this skill edits. No release has been cut yet, so until a stable cut with enhanced notes exists, the editorial style is defined by this skill's §5 rules and example transformations — not by a prior release body.

You are running in a forked, isolated context. Read and research freely — only the final summary you return reaches the main conversation. All file writes happen in this fork; the draft lands at `claude/release-notes/<tag>-draft.md`.

## When to use

- "Enhance the release notes for `0.8.0-rc.1`"
- "Look at the latest pre-release notes and refine them"
- "Help me adjust release notes for the current rc"
- "The CI just published `<tag>`, make it readable"

Do **not** trigger on requests like "what changed in 0.7.0?" — that is a recall question, not a notes-editing task.

## Inputs

`tag` = `$tag` — the GitHub release tag to enhance (e.g. `0.8.0-rc.1`, `0.9.0`). If empty, pick the most recent tag from `gh release list --limit 5` and confirm with the user before editing.

## Mode selection

After resolving the tag, decide which path to follow:

- **Merge path** — the target is a **stable** `X.Y.Z` (no `-rc` suffix) *and* one or more `X.Y.Z-rc.*` pre-releases exist whose release bodies are **already enhanced**. A stable's notes are exactly the union of its pre-releases plus whatever landed after the last rc, and those rc bodies have already been through this editorial pass — so assemble the stable draft by merging them and reserve fresh work for the post-last-rc commits. This is what "enhance `0.8.0`" means once the rc notes are done. Follow the **Merge path** section below.
- **From-scratch path** — everything else: any `-rc.N` target, a stable with no pre-releases, or a stable whose rc bodies are still raw CI output. Process every bullet from the raw notes via the **Workflow** steps below.

To tell whether an rc body is enhanced or raw, sample one (`gh release view <X.Y.Z-rc.0> --json body`): enhanced bullets read as `* <Capitalized clause> — <why> #<issue> (#<PR>)` with `—` em-dashes and backticked identifiers; raw CI bullets still carry conventional-commit prefixes (`feat:`, `fix(scope):`) or `snake_case_branch_slugs`.

## Workflow (from-scratch path)

These steps process every bullet from the raw GitHub notes. For a stable cut whose `-rc.*` notes are already enhanced, use the **Merge path** section instead (it reuses these steps only for the post-last-rc delta).

### 1. Resolve target and reference styles

1. `gh release view <tag> --json body,name,tagName` — capture the raw CI notes. The raw section headings (`## Features` / `## Fixes` / `## Other`) are emitted by the shared `epam/ai-dial-ci` `java_release.yml` workflow and are **assumed, not yet confirmed** for this repo — when the first release is published, verify the actual headings and adjust the §4 classification table if they differ.
2. `gh release list --limit 10` — locate the previous tag of the same kind (last stable for a stable release, the predecessor `rc` for a delta `rc.N+1`).
3. `gh release view <prev-stable-tag> --json body` and `gh release view <prev-rc-tag> --json body` (when relevant) — these are the style anchors. The user has repeatedly insisted **"keep the same format as the latest stable release"** and **"your notes are too verbose"** — match the terseness of those notes, not your own instincts. One line per bullet. **First-release fallback:** when no prior tag of the same kind carries enhanced notes (the case until the first cut), the style anchor is this skill's §5 rules and example transformations.
4. `git tag --list | sort -V` + `git log <prev-tag>..<tag> --oneline` — full commit list for the range, so you can spot hotfix commits the CI dropped because they had no PR.

### 2. Pull source context for each bullet

For every bullet in the raw notes:

1. Parse out the trailing `#<issue> (#<PR>)` or `(#<PR>)`. If only a PR number is present, that's the canonical reference; if both, keep `#<issue> (#<PR>)` order.
2. `gh pr view <PR> --json title,body,labels` — read the PR body, not just the title. The body is where the *why* and the *what-it-replaces* live; the title is usually too compressed.
3. For bullets without a PR number (`* fix tests`, `* application mcp endpoint path`, `* Merge remote-tracking branch ...`), find the commit with `git log <prev-tag>..<tag> --oneline | grep -i <keywords>` and `git show <hash>` — these are usually hotfix commits that should fold into a related entry, not stand alone.
4. If a PR body references a doc under `docs/designs/` or `docs/`, skim it for the headline framing.

### 3. Cross-check `README.md` / `docs/configuration.md` / source for deployment changes

The `Deployment Changes` section is built from primary sources, not PR titles:

- `git diff <prev-tag>..<tag> -- README.md docs/configuration.md` — env-var additions/removals/renames.
- `git diff <prev-tag>..<tag> -- src/main/resources/db/migration/` — Flyway migrations (meta + analytics) that landed in the range.
- For any env var that lands in the section, verify the **canonical name** by reading the actual `@ConfigurationProperties` class under `com.epam.aidial.evaluation.configuration.properties` via LSP (`goToDefinition` on the field, or `Grep` the codebase). The PR body and the code can disagree — code wins. Watch for deliberate aliases: e.g. the property `dial.api-key` binds to `DIAL_EF_API_KEY`, not the naive `DIAL_API_KEY`.
- Confirm defaults and bounds by reading the properties class's Jakarta validation annotations (`@Min`, `@Max`, `@NotBlank`, `@DecimalMin`, etc.) and the matching default in `application.yml`.

### 4. Classify each bullet (move things between sections, drop the noise)

The raw notes' `## Features` / `## Fixes` / `## Other` partition is unreliable because CI keys it off the conventional-commit prefix in the PR title. Reclassify by the change's actual user impact:

| Where CI put it | Where it belongs | Rule |
|---|---|---|
| `Other` starting with `feat(...)` | `Features` | A feat that lost its slot to a scope prefix. |
| `Other` starting with `fix(...)` | `Fixes` | Same, for fix. |
| `Features` / `Fixes` for a pre-release-only regression | `Fixes` with note "(affects pre-release users of \<feature\> only)" | Don't surface a transient bug as a feature. |
| `Other` for a security CVE bump | `Fixes` | Security items are user-relevant. |
| Multiple PRs / hotfix commits on one feature | one folded entry under the appropriate section | Cite the commit hashes or PR numbers in parens. |

**Drop these from the notes entirely** — they have no consumer-visible effect:

- Pure renames of internal classes (`chore: rename CompletionResult to TestCaseRunResult`).
- Package re-layouts (`chore: split common exceptions into a package`).
- Generated-source churn (`chore: regenerate jOOQ sources`, diffs confined to `src/main/java-generated/`).
- Formatting / static-analysis housekeeping (`chore: spotlessApply`, Checkstyle fixes, no-compile-warnings sweeps).
- Internal refactors with no behavior change (`chore: extract … helper`, `chunk processing refactoring`).
- Dependency / Gradle housekeeping that isn't security-relevant (`chore: dependency & gradle update`).
- Claude Code agent setup / docs / skill scaffolding, OpenSpec artifact churn (`init claude documentation`, `add review skill`, change-proposal edits under `openspec/`).
- Functional/integration-test additions or test-module realignments (`@PostgresFunctionalTests`, Testcontainers) that don't change product behavior.
- `Merge remote-tracking branch …` commits.
- CI-only changes (release-candidate branching, workflow edits) **unless** a maintainer needs to know — then keep under `Other` with a one-line rationale.

**Keep in `Other`** — items maintainers or operators care about even if they're not features:

- Security-adjacent dependency / base-image bumps (`bump plexus`, `upgrade musl to address CVE-…`).
- OpenAPI / API-contract doc updates visible to API consumers.
- Forwarded-headers / auth-handling / security-mode behavior checks.
- Issue templates, contributor-facing governance docs.

If you find yourself unsure whether to drop a bullet, ask: *would a customer reading these notes care that this happened?* If no, drop it.

### 5. Rewrite each kept bullet

The raw form is `* <conventional-prefix>: <branch-slug> #<issue> (#<PR>)`. Rewrite to:

```
* <Active-voice description of what changed> — <brief why-it-matters or what-it-replaces> #<issue> (#<PR>)
```

Rules in order of importance:

1. **One line per bullet.** No multi-paragraph descriptions. The user has explicitly flagged "too verbose" in prior runs. If you need more detail, save it to the companion editorial-notes file (see §8), not the main draft.
2. **Drop the conventional prefix** (`feat:`, `fix:`, `chore:`, `fix(time-awareness):`). Replace with prose.
3. **Drop branch-style slugs.** `async-dial-instead-of-dial-core-client` → `Replace dial-core client with async-dial`. The PR title is the prompt, not the output.
4. **Use a `—` em-dash for the "why" clause**, not a hyphen or colon.
5. **Backticks for code identifiers**: env vars (`TEST_SUITE_RUN_SSE_TIMEOUT_MINUTES`), file paths, config property keys (`metric-providers.sync.cron`), class names, Flyway migration names, schema keys.
6. **Preserve issue + PR refs at the end** in `#<issue> (#<PR>)` order, or `(#<PR>)` when there is no issue. Don't strip them — these notes ship as the GitHub release body where the numbers auto-link.
7. **Flag regressions explicitly**: `(regression fix)` for items restoring previously-working behavior.
8. **Quote CVE IDs verbatim** for security upgrades: `Upgrade musl to 1.2.5-r23 to address CVE-2026-40200`.

> This project has no preview-feature gating (no `ENABLE_PREVIEW_FEATURES` flag or per-app feature manifests). If one is later introduced, reinstate the QuickApps conventions: a `[Preview]` prefix for preview-gated features and a `Graduate <feature> to GA — <gate removed>` lead for graduations.

#### Example transformations

Each pair is `raw CI` → `enhanced`. Backticks in the enhanced form denote code identifiers in the actual output.

```
# Adding a "what changed" clause, dropping `feat:`:
- * feat: introduce dataset entity #229 (#230)
+ * Introduce the `Dataset` entity — test suites bind to a reusable dataset instead of carrying inline test cases #229 (#230)

# Replacing a branch slug with the concrete mechanism:
- * feat: configurable-test-suite-run-executor-pool #200 (#206)
+ * Configurable test-suite-run executor pool — `TEST_SUITE_RUN_EXECUTOR_CORE_POOL_SIZE` replaces the fixed worker count #200 (#206)

# Removing `fix:` and the bare `(53)` mistake, restoring the issue ref:
- * fix: plexus vulnerability (53)
+ * Upgrade `plexus-utils` to address the path-traversal advisory #52 (#53)

# Marking a regression fix and naming the precise gap:
- * fix application startup after url migration (#57)
+ * Restore application startup after the migration off the deprecated `new URL(..)` constructor (regression fix) (#57)

# Reclassified (raw had it in Other because of `fix(scope):` prefix):
- * fix(dataset): cannot delete dataset with linked test suites (#54)
+ * Allow deleting a dataset that still has linked test suites — unbinds the suites first instead of failing with a constraint violation (#54)

# Folded orphan hotfix commits into a related fix entry:
- * fix sse double-quoted json        (orphan commit, no PR)
- * fix tests                          (orphan commit, no PR)
+ * Stop double-quoting JSON in the SSE stream response — run progress events now parse on the client (`27a0340`, `1500580`)
```

### 6. Build the `Deployment Changes` section

Add this section **only** when the range introduces at least one env-var, behavioral, or schema change. Pick subsections — include only the ones with entries:

```markdown
## Deployment Changes

### New environment variables
<table: Variable | Default | Description>

### Deprecated environment variables
> [!CAUTION]
> Still works, but will be removed in future versions.
<table: Variable | Replacement | Description>

### Removed environment variables
<table: Variable | Reason>

### Behavioral changes
> [!NOTE]
> <one-line explaining the behavioral shift, e.g. a changed default or error-handling shift>
- **<Feature>** — <field / module> (#<PR>)

### DIAL Configuration changes
> [!IMPORTANT]
> <one-line stating the operator-facing change>
>
> **Required migration** (#<PR>):
>
> 1. **Remove** <the legacy config block in DIAL Core / external system>.
> 2. **Add** <the new config block(s)> to <exact location in the external system, e.g. a DIAL Core deployment entry>. Apply the snippet from PR #<PR> verbatim.

**Don't enumerate sub-properties of a single block.** Name the top-level config key the operator is pasting in — and stop there. Inner fields, nested sub-properties, or values that ship inside that block are not separate operator actions; they are payload the operator gets for free by copying the snippet. Mentioning them at the same level as the parent block misleads the reader into thinking they are independent additions. If a key matters enough to flag, it must be a peer of the block being added — verify nesting against the actual schema snippet (read the PR diff) before listing it.

### Database migrations
> [!NOTE]
> Flyway applies these automatically on startup; the service account needs DDL privileges on both schemas.
- **meta** — `V1.NN__<Name>.sql` — <one-line of what it changes> (#<PR>)
- **analytics** — `V1.NN__<Name>.sql` — <one-line> (#<PR>)

> [!CAUTION]
> Flag any **destructive or irreversible** migration here (column/table drops, non-nullable backfills, type narrowing) — operators may want a backup or a maintenance window. Remember to update `docs/database-schema.md` in the same PR.

### API / contract changes
> [!NOTE]
> Note removed/renamed endpoints, changed request/response DTO shapes, or status-code changes that break existing API consumers. Point readers at Swagger (`/swagger-ui.html`). (#<PR>)
```

#### Which subsection: telling Behavioral, DIAL Configuration, and Database migrations apart

These look adjacent but answer different operator questions:

- **Behavioral changes** — *"How does the deployed service behave differently at runtime?"* Default values changing, validation-bound changes, dual-datasource behavior, error-handling shifts. Operator does nothing; the change is automatic on upgrade. Uses `> [!NOTE]`.
- **DIAL Configuration changes** — *"What must I change in DIAL Core's configuration (or another external system) for this release to work?"* Migrations that operators must apply by hand outside this repo. Uses `> [!IMPORTANT]` and a numbered **Required migration** list with literal config keys. Don't bury this in Behavioral changes — operators will miss it.
- **Database migrations** — *"What schema changes will Flyway apply on startup, and is any of them destructive?"* In-repo Flyway migrations under `src/main/resources/db/migration/{meta,analytics}/POSTGRES/`. Operators don't run them by hand (auto-applied), but they need to know about DDL-privilege and destructive-change implications. Uses `> [!NOTE]` plus a `> [!CAUTION]` for destructive ones.

If a change requires the operator to touch a config file outside this repo (DIAL Core deployment config, helm values), it belongs in **DIAL Configuration changes** with a concrete remove/add migration list, never in Behavioral changes.

**Crucial — what does *not* belong here**: per-entity / request-body fields (new test-suite config fields, dataset schema keys, per-run options). Those changes belong in the **Features** bullet body where they're introduced. `Deployment Changes` is for operator-facing concerns: env vars, behavioral shifts, external-config migrations, database migrations, and API-contract breaks. Don't promote an app-level data field to a deployment concern.

For the env-var tables, the description column comes from `docs/configuration.md` (the `Description` column of its six-column table); cite the `Required` value verbatim using that doc's four-term vocabulary (Yes / No / Conditional / Recommended). Confirm bounds (`> 0`, `0 < x ≤ 3600`) from the `@ConfigurationProperties` validation annotations. For dual-datasource changes, list both the `*_META_*` and `*_ANALYTICS_*` variables.

### 7. Pre-release / delta handling

If the target is `<X.Y.Z>-rc.N` with `N ≥ 1`:

- The release covers only what changed since the previous rc — do **not** consolidate or rewrite the predecessor's notes. Each pre-release tag has its own GitHub release page; the consolidation happens at the stable cut (see the **Merge path** section).
- Drop sections that have no entries in the delta (e.g. no `Deployment Changes` if no env vars or schema changes landed in this rc).
- Do **not** prepend a "Delta since <prev-rc>" pointer at the top. The CI doesn't emit one, the previously-shipped rc notes don't carry one, and the `-rc.N` version suffix already signals what the release is. Adding a header just creates editorial noise the user has to clean up.

### 8. Save the draft (and optional editorial companion)

Create `claude/release-notes/` if missing, then write:

- **`claude/release-notes/<tag>-draft.md`** — the final notes, ready to paste into the GitHub release body. No preamble, no commentary — just the headings and bullets.
- **`claude/release-notes/<tag>-editorial-notes.md`** *(optional)* — only when there are non-obvious calls worth surfacing to the user:
    - Rename mapping (raw bullet → enhanced bullet) for items where the rewrite is non-trivial.
    - List of items dropped, with one-line reason per item.
    - Open questions for the user (e.g. "Should `#236` CI workflow PR stay under Other? It's invisible to consumers but visible to release maintainers.").
    - Any place the source-of-truth diverged from the PR body (e.g. canonical env-var name).

### 9. Verify nothing was pushed to GitHub

This skill **never** runs `gh release edit`, `gh release create`, or any write operation against the repo. The user explicitly directed: *"Everything should be drafted in local files. Don't push anything or change anything in GitHub."* Draft files are the only output. If the user later asks you to apply, that is a separate, explicit request.

## Merge path — assembling a stable cut from enhanced rc notes

A stable `X.Y.Z` ships exactly what its `X.Y.Z-rc.*` pre-releases shipped, plus whatever landed between the last rc and the stable tag. Each rc release body has **already** been through this skill — every bullet is classified, rewritten, and deduped within its own delta. Re-deriving all of that from the raw stable notes throws away wording the user already approved and invites drift. So reuse the rc bullets and reserve fresh work for the post-last-rc commits only.

The raw stable GitHub notes still matter here — not as a bullet source, but as the authoritative **inventory** of what's in the release, so you can prove nothing slipped through the merge.

### M1. Gather the rc chain and the stable inventory

1. `gh release list --limit 30` — every `X.Y.Z-rc.*` tag for this version (ordered `rc.0`, `rc.1`, …) and the previous stable `X.Y'.Z'`.
2. `gh release view <X.Y.Z-rc.N> --json body` for each rc — these enhanced bodies are the bullet source.
3. `gh release view <X.Y.Z> --json body` — the raw stable notes, used as an inventory checklist. Skip if the stable release isn't published yet and use the git range below as the inventory instead.
4. `git log <prev-stable>..<X.Y.Z> --oneline` (full range) and `git log <last-rc>..<X.Y.Z> --oneline` — the second is the **post-last-rc delta**, the only commits no rc has seen.

### M2. Confirm the rc bodies are enhanced

Sample each (see *Mode selection* for the enhanced-vs-raw tell). If one rc is still raw, enhance that rc's delta first — Steps 2–6 over `git log <prev-rc>..<rc>` — before merging it. If most rcs are raw, abandon the merge and run the whole from-scratch **Workflow** on the stable instead.

### M3. Union the bullets

Collect the `Features`, `Fixes`, and `Other` bullets from every rc body. **Copy the enhanced wording exactly — do not re-fetch the PR or rewrite it.** The editorial work is already done; your job is assembly, not re-authoring. Preserve each bullet's trailing `#<issue> (#<PR>)` references character-for-character as the rc wrote them — don't re-parenthesize, reorder, or normalize them (a stray `#283` → `(#283)` is exactly the kind of drift that creeps in when copying by eye).

For ordering, follow the established stable layout rather than a rigid rc-by-rc concatenation: lead with headline features, keep related items together, and place minor enhancements last. Ordering is low-stakes and the user reviews it — don't agonize, but don't fragment the list strictly by rc either.

### M4. Deduplicate and consolidate across the rc boundary

The rcs were edited in isolation, so the union needs reconciliation:

- **A feature touched by more than one rc** — introduced in one rc, refined in a later one (same `#PR`/`#issue`, or a follow-up PR on the same feature) — collapses to one bullet with the most complete wording. Cite both PR numbers in parens when each carried real change.
- **Pre-release-only regression fixes → drop.** When an rc bullet fixes something that broke *within this version's own pre-release cycle* — it carries an `(affects pre-release users of <feature> only)` marker, or it's an orphan `hotfix:` commit patching a feature first shipped in an earlier rc of this same version — leave it out. A consumer upgrading from the previous stable never saw the broken intermediate state, so that feature's own bullet already describes the working result. Fixes for bugs that existed in the *previous stable* are real fixes — keep those. *Example: a CSV-import coercion feature shipped in rc.0 and a hotfix corrected its delimiter handling before stable; the stable notes describe the working import and omit the hotfix.*
- **Merge the `Deployment Changes` subsections** — union all `New environment variables` rows into one table; carry over every `DIAL Configuration changes` migration, every `Database migrations` entry (meta + analytics), and every `API / contract changes` note; combine `Behavioral changes` into a single block. If two rcs list the same env var with different defaults or descriptions, reconcile to one row and verify the canonical name/default from source (§6 rule — code wins over PR bodies).

### M5. From-scratch pass on the post-last-rc delta only

For the commits in `git log <last-rc>..<X.Y.Z>` (M1.4), run Steps 2–6 of the from-scratch **Workflow** — parse refs, read PR bodies, classify, drop the noise, rewrite, and pull any new deployment changes — then fold the survivors into the unioned sections and tables. If the delta is empty, skip this step; the merge is just the reconciled rc union.

### M6. Reconcile against the stable inventory

Walk every PR/issue number in the raw stable notes (M1.3). Each must resolve to exactly one of:

- **kept** — present in the unioned rc bullets,
- **new** — handled by the M5 delta pass, or
- **dropped** — an internal item the rc passes already excluded. Re-apply §4's drop rules, and **don't resurrect** something the rcs deliberately left out just because it's missing from the union (those internal refactors were dropped on purpose, not overlooked).

Anything that fits none of these three is a genuine gap — surface it in the editorial companion rather than guessing.

### M7. Save

Write the assembled notes to `claude/release-notes/<tag>-draft.md` in the standard Output format. In `claude/release-notes/<tag>-editorial-notes.md`, record: the rcs merged (tag → bullet counts), cross-rc dedup/folds, pre-release-only fixes dropped, post-last-rc delta items added, and any inventory gaps from M6.

## Output format

The file saved to `claude/release-notes/<tag>-draft.md` follows this shape exactly:

```markdown
## Features

* <one bullet per change>

## Fixes

* <one bullet per change>

## Other

* <only consumer- or maintainer-relevant items>

## Deployment Changes

### New environment variables
<table>

### Deprecated environment variables
> [!CAUTION]
> …
<table>

### Behavioral changes
> [!NOTE]
> …
- <item>

### DIAL Configuration changes
> [!IMPORTANT]
> …
>
> **Required migration** (#<PR>):
> 1. **Remove** …
> 2. **Add** … Apply the snippet from PR #<PR> verbatim.

### Database migrations
> [!NOTE]
> …
- **meta** — `V1.NN__<Name>.sql` — … (#<PR>)
- **analytics** — `V1.NN__<Name>.sql` — … (#<PR>)

### API / contract changes
> [!NOTE]
> … (#<PR>)
```

Section order: `Features` → `Fixes` → `Other` → `Deployment Changes`. Subsections inside `Deployment Changes` appear in the order: New env vars → Deprecated env vars → Removed env vars → Behavioral changes → DIAL Configuration changes → Database migrations → API / contract changes.

A delta `rc` release uses the same shape — no preamble paragraph, no header pointing at the previous rc. A merged stable cut (**Merge path**) uses this same shape too; it just sources its bullets from the rc notes plus the post-last-rc delta rather than from the raw stable notes.

## Return to the main conversation

Return a short summary — five lines or fewer. Include:

- The draft path (`claude/release-notes/<tag>-draft.md`).
- Counts of bullets per section after enhancement.
- Reclassifications that happened (e.g. "moved 2 from Other → Features, 1 from Other → Fixes").
- Items dropped (count, with one example).
- Whether a `Deployment Changes` section was added and which subsections.
- Any open questions for the user (env-var name disagreement between PR body and source, ambiguous categorization).

For a **Merge path** run, report instead: which rcs were merged, the section counts after merging, cross-rc dedup/folds, pre-release-only fixes dropped, and what the post-last-rc delta added (or that it was empty).

Example (from-scratch):

> Drafted `claude/release-notes/0.1.0-rc.1-draft.md`. 5 Features, 3 Fixes, 4 Other. Reclassified 1 from Other → Features (`feat(metric-providers)`) and 1 from Other → Fixes (`fix(dataset)`). Dropped 3 internal items (jOOQ regen, Spotless sweep, merge commit). Added Deployment Changes with New env vars (`TEST_SUITE_RUN_EXECUTOR_CORE_POOL_SIZE`) + Database migrations (meta `V1.22`, analytics `V1.8`). One open: PR body says `DIAL_API_KEY` but the property `dial.api-key` binds to `DIAL_EF_API_KEY` — used the code name; flagged in editorial notes.

Example (merge):

> Drafted `claude/release-notes/0.1.0-draft.md` by merging rc.0 (6F/4Fx/6O), rc.1 (7F/3Fx/2O), rc.2 (2F). Result: 15 Features, 7 Fixes, 8 Other. Merged 3 env-var rows into one table; combined Behavioral changes and carried over the meta `V1.22` + analytics `V1.8` database migrations and the #54 dataset-deletion fix. Dropped the CSV-import delimiter hotfix (pre-release-only). Post-rc.2 delta was empty. One open: #51 `double-quoted SSE json` — kept or internal? flagged in editorial notes.

## Safety rails

- **Never edit GitHub.** No `gh release edit`, no `gh release create`. Drafts only.
- **Never invent items.** Every kept bullet maps to a PR or a commit hash in the range.
- **Never silently rename or drop a PR reference.** The bullet ends with the canonical `#<issue> (#<PR>)` so links resolve on the release page.
- **Verify canonical names from source**, not from PR bodies. Past PR descriptions have used pre-rename names; the code is the source of truth.
- **Each rc page stands alone; the stable cut consolidates them.** When the target is a `-rc.N`, never pull sibling-rc notes into it. When the target is the **stable cut** and the rc notes are already enhanced, merging them is the expected path (**Merge path**) — but only ever write the local stable draft; never edit or overwrite the individual rc release pages on GitHub.
- **Match the terseness of the predecessor's notes.** If they're one-liners, your bullets are one-liners. The user has flagged verbose drafts twice; defer to the established style.

## Maintenance

Conventions drift as the project grows. If you notice a pattern in the raw CI notes that this skill doesn't handle (a new section the CI emits, a new conventional-commit scope that misroutes items, a recurring rewrite the user keeps asking for), surface it in your return summary and offer to update this `SKILL.md`. The user can confirm before any edit lands.