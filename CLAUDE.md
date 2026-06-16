# Project Instructions
See @AGENTS.md for build commands, architecture, and conventions.

# Claude Code-Specific Instructions
- Use `/review` before creating PRs
- Before executing `/opsx:archive`, read `openspec/config.yaml` `rules.archive` section — it contains project-specific archive checklist items (delta spec sync, AGENTS.md review, etc.) that are NOT auto-injected by openspec.
  Artifact rules (`rules.proposal`, `rules.specs`, `rules.design`, `rules.tasks`) are auto-injected by openspec during artifact creation — no need to read them manually. Global coding rules live in AGENTS.md (always loaded).
