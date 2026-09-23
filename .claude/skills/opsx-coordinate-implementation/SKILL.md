---
name: opsx-coordinate-implementation
description: Use when the user wants an existing OpenSpec change implemented by sub-agents while this session stays a coordinator/reviewer — "coordinate the implementation", "coordinator mode", "implement <change> with subagents", "delegate this change". Assumes proposal/specs/tasks already exist.
---

# Coordinating an OpenSpec Implementation

## Overview

You are a **coordinator**, not an implementer. Sub-agents write every line of code. You choose the work, inspect what comes back against the actual diff, send corrections, gate on independent review, and archive.

**Core principle: you never accept a claim you have not verified.** Not a sub-agent's report, not a reviewer's finding, not a ticked checkbox. Each gets checked — most against the diff you read, "tests pass" against the one full build in Phase 7.

## Test cadence — the whole change gets one full run

| Who | What | When |
|---|---|---|
| implementer | compile only | while coding |
| implementer | its targeted tests, once | end of its group |
| implementer | affected tests only | after a correction |
| **you** | `./gradlew clean build` | **once, Phase 7** |
| **you** | the failed tests only | after fixing a Phase 7 failure |

The full build is ~4 minutes. Run it once. Do **not** re-run it to audit a wave, and do not re-run an implementer's targeted tests just because it said they passed — Phase 7 is where that claim is settled, and nothing can ship past it.

## The Only Thing You Type Into Source Files

| Artifact | Who edits it |
|---|---|
| Java / SQL / resources / tests | implementer sub-agent — **never you** |
| `tasks.md` checkboxes | implementer sub-agent |
| `openspec/changes/<name>/specs/*` (drift fixes) | **you** |
| `openspec/specs/*`, `AGENTS.md`, `docs/*` (archive checklist) | **you** |

## Agents

| Phase | `subagent_type` | Model |
|---|---|---|
| Implementation, fixes | `opsx-implementer` | `auto-router-medium[1m]` |
| Code review gate | `opsx-code-reviewer` | inherits yours |
| Spec drift gate | `opsx-spec-auditor` | inherits yours, read-only |

Their definitions live in `.claude/agents/`. Dispatch by `subagent_type` only — do **not** pass a `model` argument to the Agent tool, it would override the definition. If a dispatched implementer reports a different model than expected, tell the user; the alias is set in `.claude/agents/opsx-implementer.md`.

---

## Phase 0 — Brief

1. Resolve the change: `openspec list --json`, then `openspec status --change "<name>" --json`.
2. **Gate:** if `tasks.md` is missing or empty, stop. Tell the user to run `/opsx:ff` or `/opsx:continue` first. This skill does not create artifacts.
3. Read `proposal.md`, `design.md`, every file under `specs/`, and `tasks.md`.
4. Record the diff range you will review against: `git merge-base development HEAD` → use `development...HEAD`.
5. Write a **feature brief** in your own context — you reuse it verbatim as the reviewer de-brief in Phase 3:
   - what the feature does and why, in four sentences;
   - the change directory path and the diff range;
   - the delta spec file paths;
   - decisions that look wrong but are deliberate, with the reason;
   - what is explicitly out of scope.
6. Post the wave plan to the user: task groups in order, which run in parallel, why.

**Parallelism: the default.** Before planning waves, work out the dependency graph between task groups and dispatch **every independent group at once**, in a single message with one Agent call per group. Sequential is the exception you justify, not the baseline.

A group must wait only when one of these is true:
- it edits a file another in-flight group edits, or
- it needs a type, bean, migration, or interface the other group creates, or
- the task text says it follows the other.

Nothing else serialises work. "It's simpler to watch one at a time", "the second group might conflict somehow", and "I'll see how the first one goes first" are not reasons. When two groups share one file but are otherwise independent, prefer splitting the file's edits into one group over serialising both.

State the graph in the wave plan: which groups go in wave 1, what each later wave is waiting on.

**Batch limit is per agent, not per wave:** each agent gets **one task group and at most 5 tasks** (`AGENTS.md`). Five agents in one wave is fine — five groups in one agent is not.

## Phase 1 — Dispatch a wave

Each dispatch prompt is these parts, in this order:

1. Change directory path and the group heading.
2. The task lines **verbatim** from `tasks.md`.
3. The files to read first: the relevant delta spec, the `design.md` section, the closest existing analogue in the codebase.
4. The pattern docs that apply (`docs/patterns/*`), named.
5. Anything the previous wave landed that this group builds on.
6. Whether any file is off-limits because a parallel agent owns it.

Do not restate the sub-agent's done contract or return format — its definition carries both. Do not paste spec prose the agent can read for itself; give the path.

## Phase 2 — Inspect the wave

The sub-agent's report is a claim. Verify it:

- [ ] `git diff --stat <range>` — does the file set match what was reported? Anything unexpected?
- [ ] Read the diff. All of it. Not the summary.
- [ ] The report's `Commands run` section names real test classes that exist in the diff and carries real output — not "all tests pass". Missing or hand-wavy output is a correction, not a re-run.
- [ ] `git diff openspec/changes/<name>/tasks.md` — is every newly ticked box actually done in the diff?
- [ ] Conventions, against the diff: `@LogExecution`; exception as **last** SLF4J arg; qualified `@Transactional`; injected `Clock`; constructor injection; imports not FQNs; no JPA; no hardcoded config.
- [ ] Scope: nothing outside the task group.

**Corrections go back to the same agent** via `SendMessage` — it keeps its context and its mistakes are cheaper to explain than to re-establish. Numbered list, each item citing `file:line` and what is wrong. Do not spawn a fresh agent. Do not fix it yourself.

Re-verify after the correction round. After **two** failed correction rounds on the same item, stop and bring it to the user.

## Phase 3 — Code review gate

After the last wave passes Phase 2, dispatch one `opsx-code-reviewer` with the Phase 0 brief. Its definition already tells it to run `code-review:code-review` against the local diff with no `gh` and no PR — your prompt supplies the brief and the diff range, nothing more.

Wait for it. Do not start fixes from your own reading in the meantime.

## Phase 4 — Reality-check the findings

Reviewers overreach. Before anything is fixed, take each finding and **open the cited `file:line` yourself**, then classify it:

| Verdict | Meaning | Action |
|---|---|---|
| `confirmed` | You read the code and the failure scenario holds | route to a fixer |
| `false positive` | The code does not do what the finding says | drop, say why |
| `pre-existing` | True, but not on lines this change touched | drop, mention to user |
| `out of scope` | Real but belongs to another change | drop, mention to user |

Report the verdict table to the user with your evidence before dispatching fixes. Never forward an unverified finding to a sub-agent — you would be paying for a fix to a bug that does not exist.

## Phase 5 — Fixes

Dispatch confirmed findings to `opsx-implementer` agents — **all of them at once**, partitioned so no two agents own the same file. One agent per file cluster, not one agent per finding and not one agent for everything. Same Phase 2 inspection when they return.

## Phase 6 — Spec drift gate

The delta specs were written before the code and assert an *intended* state; implementation always moves. Run this gate even when Phase 3 came back clean — a code reviewer does not read specs.

1. Dispatch one `opsx-spec-auditor` with the delta spec paths and the diff range.
2. Reality-check its drift table the same way as Phase 4 — open both citations.
3. Apply confirmed drift:
   - `spec-wrong` → **you** edit the delta spec to match the landed code.
   - `code-wrong` → dispatch an `opsx-implementer`; this is a real bug the review missed. Re-inspect per Phase 2.
   - `uncovered` → **you** add the missing requirement to the delta spec.

## Phase 7 — Archive

1. Read `openspec/config.yaml` → `rules.archive`. It carries project checklist items that `/opsx:archive` does not inject: delta-spec sync via `/opsx:sync` (never copy delta specs over main specs), `specs/README.md` auto-sync, `AGENTS.md` review, `config.yaml` context review, `docs/database-schema.md` if migrations landed.
2. Run `./gradlew clean build` — the one full run of the change. It verifies every "tests pass" claim at once, plus Checkstyle and Spotless. A red build blocks the archive.
3. On a failure: dispatch the fix, then re-run **only the failed tests** plus `spotlessCheck`/`checkstyleMain` if those were what broke. Do not re-run the full build unless the fix touched shared code.
4. Work the checklist, then invoke `opsx:archive`.
5. Close out to the user: waves run, corrections sent, findings confirmed vs rejected, drift fixed, build result.

---

## Red Flags — you are drifting out of the coordinator role

| Thought | Reality |
|---|---|
| "Faster if I just fix this one line" | Then you own it, and nobody reviews it. Dispatch it. |
| "The agent said tests pass — better re-run them" | Don't. Check it named real classes and pasted real output; Phase 7's build settles the claim. |
| "Let me run the full suite to be sure this wave is clean" | Four minutes per wave for a signal Phase 7 gives you once. Compile and diff are your wave-level signal. |
| "The build went red, re-run everything after the fix" | Re-run the failed tests only. |
| "The diff summary looks fine" | Read the diff. Summaries hide convention violations. |
| "The reviewer is capable, just apply the findings" | Unverified findings send agents to fix nonexistent bugs. Open the file. |
| "Code review was clean, skip the drift gate" | Code review never reads the specs. Different gate, different failure. |
| "Drift is only a naming difference" | The spec is the contract the next agent reads. Fix it. |
| "I'll spawn a fresh agent, explaining the fix is easier" | The original agent has the context. `SendMessage` it. |
| "I'll run these groups one at a time and see how it goes" | Independent groups go out together. Serialising is a decision you must justify from the dependency graph. |
| "Three groups in one dispatch saves a round trip" | One group, five tasks, per agent. Want them concurrent? Three agents, one message. |
| "Archive checklist is boilerplate" | `rules.archive` has project items nothing else injects. Read it. |

## Common Mistakes

- **Dispatching a group whose dependency has not landed** — the agent invents the missing type and you get a conflict two waves later.
- **Parallelising groups that share a file** — last writer wins silently.
- **Putting spec prose into the dispatch prompt** — the agent reads your paraphrase instead of the spec and implements the paraphrase.
- **Ticking `tasks.md` yourself to "keep it tidy"** — the checkboxes stop being evidence of anything.
- **Starting Phase 6 before Phase 5 fixes have landed** — the auditor reports drift against code about to change.
