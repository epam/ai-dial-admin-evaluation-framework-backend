## 1. Dockerfile and entrypoint

- [x] 1.1 Create `eval-cli/Dockerfile` (multi-stage: `gradle:9.6.0-jdk25-alpine` build stage running `gradle --no-daemon :eval-cli:clean :eval-cli:bootJar`; `amazoncorretto:25-alpine` runtime stage, non-root `appuser`, no `EXPOSE`/`HEALTHCHECK`). Done when `docker build -f eval-cli/Dockerfile -t eval-cli:local .` succeeds from the repo root.
- [x] 1.2 In the runtime stage, copy the built jar as `./eval-cli.jar` (not `app.jar`) and pre-create/own `/app/eval-cli-work` for the default `cli.work-dir`. Done when the image's `/app` contains `eval-cli.jar` owned by `appuser`.
- [x] 1.3 Create `eval-cli/docker-entrypoint.sh` forwarding `"$@"` to `java -jar eval-cli.jar` (keep the existing cert-import hook and `DEBUG_OPTS`/`JAVA_OPTS` passthrough from the root app's entrypoint for consistency). Done when `docker run --rm eval-cli:local --help` prints picocli's root help and exits 0.
- [x] 1.4 Smoke test a real subcommand end-to-end: `docker run --rm -e EVAL_TOKEN=<test> -e DIAL_CORE_URL=<test> -e DIAL_CORE_API_KEY=<test> eval-cli:local evaluate --suites <uuid> --clone-suffix eval --deployment-id <id>` against a local/mock EF+DIAL Core, confirming arg passthrough and exit-code propagation.

## 2. Revert automated publishing (previously attempted, reverted per DevOps feedback)

- [x] 2.1 Delete `.github/workflows/eval-cli-release.yml`.
- [x] 2.2 Revert `.github/workflows/deploy-development.yml`'s `registry_package` trigger condition back to its original, pre-eval-cli-distribution content (drop the `package.name` filter — nothing left to protect against with no publish workflow).
- [x] 2.3 Revert `.github/workflows/cleanup-untagged-images.yml` back to its original content (drop the `packages:` list — no `eval-cli` package is ever published, so listing it risked the nightly job failing on a non-existent package).

## 3. Documentation

- [x] 3.1 Rewrite the "Docker" usage section in `eval-cli/README.md` to show `docker build` + `docker run` against a locally built image (no registry pull), documenting the clone-at-a-pinned-ref pattern for external CI consumers.
- [x] 3.2 Update `openspec/config.yaml`'s Tooling section entry to describe the build-only (no publish) model.
- [x] 3.3 Update `openspec/specs/README.md`'s `eval-cli-distribution` entry, and the main synced spec `openspec/specs/eval-cli-distribution/spec.md`, to remove the publishing/isolation requirements and add the "No Automated GHCR Publishing" requirement.

## 4. Final verification

- [x] 4.1 Confirm `release.yml`, the root `Dockerfile`, and root `docker-entrypoint.sh` are unmodified, and that `deploy-development.yml`/`cleanup-untagged-images.yml` are back to their exact original content (diff against pre-eval-cli-distribution history) — no lingering changes to the main app's build/publish/deploy pipeline.
- [x] 4.2 Re-run the local build (task 1.1) and smoke tests (tasks 1.3, 1.4) once more against the final Dockerfile/entrypoint, to confirm nothing regressed while reverting the publishing workflow.
