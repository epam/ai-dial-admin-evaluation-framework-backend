## 1. Dockerfile and entrypoint

- [x] 1.1 Create `eval-cli/Dockerfile` (multi-stage: `gradle:9.6.0-jdk25-alpine` build stage running `gradle --no-daemon :eval-cli:clean :eval-cli:bootJar`; `amazoncorretto:25-alpine` runtime stage, non-root `appuser`, no `EXPOSE`/`HEALTHCHECK`). Done when `docker build -f eval-cli/Dockerfile -t eval-cli:local .` succeeds from the repo root.
- [x] 1.2 In the runtime stage, copy the built jar as `./eval-cli.jar` (not `app.jar`) and pre-create/own `/app/eval-cli-work` for the default `cli.work-dir`. Done when the image's `/app` contains `eval-cli.jar` owned by `appuser`.
- [x] 1.3 Create `eval-cli/docker-entrypoint.sh` forwarding `"$@"` to `java -jar eval-cli.jar` (keep the existing cert-import hook and `DEBUG_OPTS`/`JAVA_OPTS` passthrough from the root app's entrypoint for consistency). Done when `docker run --rm eval-cli:local --help` prints picocli's root help and exits 0.
- [x] 1.4 Smoke test a real subcommand end-to-end: `docker run --rm -e EVAL_TOKEN=<test> -e DIAL_CORE_URL=<test> -e DIAL_CORE_API_KEY=<test> eval-cli:local evaluate --suites <uuid> --clone-suffix eval --deployment-id <id>` against a local/mock EF+DIAL Core, confirming arg passthrough and exit-code propagation.

## 2. CI publishing workflow

- [x] 2.1 Create `.github/workflows/eval-cli-release.yml`: triggers on push to `development`/`release-*` (path-filtered to `eval-cli/**`, `evaluation-runner-core/**`, `build.gradle`, `settings.gradle`, `gradle.properties`) plus `workflow_dispatch`; `permissions: contents: read, packages: write`.
- [x] 2.2 Add `docker/login-action` (GHCR, `secrets.GITHUB_TOKEN`), `docker/metadata-action` (tags: `${{ github.ref_name }}` and `sha-<short>` — plain, consistent with the main app's scheme), and `docker/build-push-action` (`context: .`, `file: eval-cli/Dockerfile`, `push: true`) steps, all actions pinned to commit SHAs per this repo's existing convention (see `cleanup-untagged-images.yml`).
- [x] 2.5 Confirm whether `cleanup-untagged-images.yml`'s `dataaxiom/ghcr-cleanup-action` sweeps the new `eval-cli` package automatically or needs an explicit per-package entry; update the workflow if needed (open question from design.md). Confirmed: defaults to only the repo-named package, so `packages:` was added explicitly listing both.
- [x] 2.6 Add a `github.event.registry_package.package.name == 'ai-dial-admin-evaluation-framework-backend'` clause to `deploy-development.yml`'s `registry_package` trigger condition — this is the actual isolation mechanism (self-contained in the deploying workflow), replacing the earlier tag-prefix-based approach and allowing eval-cli's tags to stay plain/consistent with the main app.

## 3. Documentation

- [x] 3.1 Add a "Docker" usage section to `eval-cli/README.md` showing `docker run` with the same env vars as the existing `java -jar` quick start, plus the `cli.work-dir` volume mount and the `development` tag example.
- [x] 3.2 Update `openspec/config.yaml` Tooling section to add the new `docker build -f eval-cli/Dockerfile -t eval-cli:local .` command, alongside the existing root `docker build -t dial-eval-backend .` entry (new tooling command introduced, per Config Maintenance Policy).
- [x] 3.3 Update `openspec/specs/README.md` per the Spec Index Maintenance Policy — add the new `eval-cli-distribution` spec folder to the index.

## 4. Final verification

- [x] 4.1 Confirm `release.yml`, the root `Dockerfile`, and root `docker-entrypoint.sh` are unmodified, and that `deploy-development.yml`'s only change is the additive `package.name` filter from task 2.6 (diff review) — no other changes to the main app's build/publish/deploy pipeline.
- [x] 4.2 Re-run the local build (task 1.1) and smoke tests (tasks 1.3, 1.4) once more against the final Dockerfile/entrypoint before merging, to confirm nothing regressed during CI workflow tuning.

## Post-merge verification (manual, after this change is merged to `development` — not tracked as blocking tasks)

- Verify `eval-cli-release.yml` does NOT fire for commits that don't touch the filtered paths (`eval-cli/**`, `evaluation-runner-core/**`, root Gradle files), and DOES fire for commits that do.
- Confirm a real `eval-cli-release.yml` run (via `workflow_dispatch` or a push touching a filtered path) succeeds end-to-end and the image is visible in the `ghcr.io/epam/eval-cli` GHCR package, tagged `development` and `sha-<short>`. This is currently unverified from this repo — only local `docker build`/`docker run` smoke tests (tasks 1.1, 1.4, 4.2) have been performed; whether the default `GITHUB_TOKEN` can create/push to this new top-level org package on first run is untested (see design.md Open Questions). If the push fails with a permissions error, escalate to an org admin to adjust `epam` org package-creation policy, or switch to a PAT with broader `packages:write` scope.
- Verify that `deploy-development.yml` did NOT run as a side effect of the eval-cli publish above (confirms the package-name isolation requirement from specs/eval-cli-distribution/spec.md).
