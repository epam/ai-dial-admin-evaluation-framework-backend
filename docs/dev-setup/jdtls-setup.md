# jdtls Setup & Enhancement Journal

Setup journal for the Eclipse JDT Language Server used by Claude Code on this machine.
Useful as a reference when re-setting up the environment or onboarding colleagues.

---

## Environment

| Component | Value |
|---|---|
| OS | Ubuntu 22.04 (WSL2) |
| Java | OpenJDK 25 at `/usr/lib/jvm/java-25-openjdk-amd64` |
| jdtls version | 1.58.0.202604151538 |
| jdtls install dir | `~/.local/share/jdtls/` |
| Wrapper script | `~/.local/bin/jdtls` |
| LSP client | Claude Code plugin `jdtls-lsp@claude-plugins-official` |
| Workspace cache | `~/.cache/jdtls/jdtls-{sha1(project-dirname)}/` |

### Workspace cache path formula

```bash
# Run from your project root:
python3 -c "import hashlib,os; print(f\"{os.environ['HOME']}/.cache/jdtls/jdtls-{hashlib.sha1(os.path.basename(os.getcwd()).encode()).hexdigest()}\")"
```

---

## Wrapper script (current state)

`~/.local/bin/jdtls`:

```bash
#!/bin/bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
exec "$HOME/.local/share/jdtls/bin/jdtls" \
  --jvm-arg="-javaagent:$HOME/.local/share/jdtls/lombok.jar" \
  --jvm-arg="-Dosgi.locking=none" \
  "$@"
```

---

## LSP capabilities after all changes

| Operation | Status | Notes |
|---|---|---|
| `hover` | ✅ | Full Javadoc, including Spring/library classes |
| `documentSymbol` | ✅ | All class/method/field symbols in a file |
| `findReferences` | ✅ | Cross-file, includes test files, Lombok-aware |
| `goToDefinition` (project) | ✅ | Navigates to source in project, Lombok-aware |
| `goToDefinition` (library) | ❌ | Plugin limitation — `jdt://` URI scheme not handled |
| `incomingCalls` | ✅ | Who calls this method — Lombok-aware |
| `outgoingCalls` | ✅ | What this method calls — Lombok-aware |
| `goToImplementation` | ✅ | Finds all implementations of an interface |
| `workspaceSymbol` | ❌ | Plugin limitation — empty query sent, jdtls returns nothing |
| Diagnostics | ✅ | Null-safety, resource leaks, dead code, unused code |
| Spring Boot bean nav | ❌ | Blocked — see Change 4 |

### Plugin limitations (require changes to `jdtls-lsp@claude-plugins-official`)

**`workspaceSymbol`**: The plugin sends an empty query string to `workspace/symbol`.
jdtls returns nothing for empty queries by design (it would be too large). The plugin
needs to either pass a meaningful query or expose a query parameter.

**`goToDefinition` on library code**: jdtls returns `jdt://` URIs for decompiled
library classes (using the bundled JetBrains decompiler). The Claude Code plugin does
not handle `jdt://` URIs, so navigation to library classes always fails silently.
`hover` on library symbols DOES work and returns full Javadoc — use it instead of
`goToDefinition` to inspect Spring/library APIs.

---

## Changes

### Change 1 — Lombok annotation processing
**Date:** 2026-04-22

**Problem:** jdtls reported 18 false-positive compilation errors across 5 files.
All came from Lombok-generated code (`@Builder`, `@Getter`, `@Data`, `@Slf4j`,
`@RequiredArgsConstructor`) not being visible to jdtls.

**Root cause:** jdtls runs in its own JVM, separate from the Gradle build process.
The project uses the `io.freefair.lombok` Gradle plugin which works for `./gradlew build`,
but jdtls needs the Lombok Java agent (`-javaagent:lombok.jar`) passed to its own JVM at
startup to enable annotation processing inside the language server.

**Files changed:**
- `~/.local/share/jdtls/lombok.jar` — Lombok JAR copied here from Gradle cache
- `~/.local/bin/jdtls` — wrapper updated to pass `--jvm-arg="-javaagent:...lombok.jar"`

**Fix procedure:**

1. Copy the Lombok JAR from Gradle cache to a stable location:
   ```bash
   LOMBOK_JAR=$(find ~/.gradle/caches -name "lombok-*.jar" | grep -v sources | grep -v plugin | sort -V | tail -1)
   cp "$LOMBOK_JAR" ~/.local/share/jdtls/lombok.jar
   echo "Copied: $LOMBOK_JAR"
   ```

2. Add the agent to the wrapper script `~/.local/bin/jdtls`:
   ```bash
   #!/bin/bash
   export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
   exec /home/$USER/.local/share/jdtls/bin/jdtls \
     --jvm-arg="-javaagent:/home/$USER/.local/share/jdtls/lombok.jar" \
     "$@"
   ```

**Verify:** `hover` on a Lombok-generated getter returns `ReturnType getField()`.
Previously-broken files show zero errors.

**Maintenance:** When Lombok version changes in `build.gradle`, re-run step 1 to
replace the JAR at the stable path. The wrapper script path is version-independent and
does not need updating.

---

### Change 2 — WSL2 Buildship lock fix (`workspaceSymbol` + library navigation)
**Date:** 2026-04-23

**Problem:** `workspaceSymbol` returned no results. `goToDefinition` on Spring/library
classes failed. The project `.classpath` uses `org.eclipse.buildship.core.gradleclasspathcontainer`,
which needs Buildship to successfully start before the classpath (and thus symbol index)
is populated.

**Root cause:** jdtls workspace log showed:
```
BundleException: Unable to acquire state change lock for org.eclipse.buildship.core
Caused by: TimeoutException: Timeout after waiting 30 seconds
```
This is a known issue on WSL2. POSIX file locking on WSL2 filesystem mounts can be
unreliable — when jdtls exits between sessions, OSGi workspace lock files are sometimes
not released promptly. The next startup times out waiting for the lock, which prevents
the Gradle classpath container from being resolved.

**Files changed:**
- `~/.local/bin/jdtls` — added `--jvm-arg="-Dosgi.locking=none"`
- `~/.cache/jdtls/jdtls-{hash}/` — deleted for clean re-import

**Fix procedure:**

1. Add `-Dosgi.locking=none` to the wrapper script:
   ```bash
   #!/bin/bash
   export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
   exec /home/$USER/.local/share/jdtls/bin/jdtls \
     --jvm-arg="-javaagent:/home/$USER/.local/share/jdtls/lombok.jar" \
     --jvm-arg="-Dosgi.locking=none" \
     "$@"
   ```
   `-Dosgi.locking=none` disables OSGi workspace file locking — safe on WSL2
   where only one jdtls instance accesses the workspace at a time.

2. Clear the stale workspace cache from the project root:
   ```bash
   rm -rf $(python3 -c "import hashlib,os; print(f\"{os.environ['HOME']}/.cache/jdtls/jdtls-{hashlib.sha1(os.path.basename(os.getcwd()).encode()).hexdigest()}\")")
   ```

**Verify:** After next jdtls startup, `workspaceSymbol` returns project classes.
`goToDefinition` on `@Service` or `@Transactional` navigates to Spring source.

**Note:** `-Dosgi.locking=none` is safe only when a single jdtls instance is used per
workspace. Do not use if running multiple editors simultaneously against the same project.

---

### Change 3 — Enhanced JDT diagnostics
**Date:** 2026-04-23

**Problem:** jdtls used minimal diagnostic settings. Useful warnings (potential null
dereferences, resource leaks, dead code) were not reported.

**File changed:** `.settings/org.eclipse.jdt.core.prefs` in the project root.
This file is committed to the repo and applies to all team members using Eclipse or jdtls.

**Settings added:**

| Setting | Level | Rationale |
|---|---|---|
| `nullReference` | error | Definite null dereference — should never happen |
| `potentialNullReference` | warning | Potential NPE risk worth reviewing |
| `redundantNullCheck` | warning | Signals incorrect assumptions in code |
| `unclosedCloseable` | warning | Definite resource leak |
| `potentiallyUnclosedCloseable` | warning | Potential resource leak (e.g., early return) |
| `deadCode` | warning | Unreachable code after return/throw |
| `unusedLocal` | warning | Unused local variable |
| `unusedImport` | warning | Unused import (also caught by Checkstyle) |

**Note:** Null analysis here is flow-based, not annotation-based. It catches patterns
like `if (x != null) x.foo()` where `x` could be null but flags are not needed everywhere.
This does NOT require adding `@NonNull`/`@Nullable` annotations to the codebase.

---

### Change 4 — Spring Boot Language Server (PENDING)
**Date:** 2026-04-23 | **Status:** Blocked — not implemented

**What it would add:**
- Navigate from `@Autowired`/`@Qualifier` directly to the bean definition
- Navigate from a `@ConfigurationProperties` class to its `application.yml` keys
- Diagnostics: missing beans, ambiguous injections, unmapped properties
- `hover` showing which beans satisfy an injection point

**Why it is blocked:**
The STS4 Spring Boot Language Server integrates with jdtls via the `bundles` field in
the LSP `initialize` request's `initializationOptions`. The Claude Code `jdtls-lsp` plugin
does not currently expose this option — only `--jvm-arg` can be injected via the wrapper
script. Dropping JARs into `~/.local/share/jdtls/plugins/` does NOT work because jdtls
uses `osgi.bundles` from its managed `config.ini` and does not auto-scan the plugins
directory for new bundles at runtime.

**When the plugin adds `bundles` support, install steps:**

1. Download the Spring Boot Tools VSCode extension VSIX from the STS4 releases page.

2. Extract it (it is a ZIP file):
   ```bash
   unzip vscode-spring-boot-*.vsix -d spring-boot-ls-extracted
   ```

3. The `bundles` init option needs these JARs from `extension/jars/`:
   - `jdt-ls-extension.jar` — jdtls OSGi glue bundle (required)
   - `jdt-ls-commons.jar` — shared commons
   - `io.projectreactor.reactor-core.jar` — Reactor Core dependency
   - `org.reactivestreams.reactive-streams.jar` — Reactive Streams API

4. The main language server process (`extension/servers/spring-boot-language-server-*.jar`)
   is started separately by the client; it does not run inside jdtls.

5. Configure the Claude Code jdtls-lsp plugin to pass these JAR paths in `bundles`.

---

## Troubleshooting

### Lombok errors (builder(), setX(), log field, etc.) appear in diagnostics

The Lombok agent is missing or pointing to the wrong JAR.

```bash
# Check wrapper has the agent
grep "javaagent" ~/.local/bin/jdtls

# Check the JAR exists
ls -lh ~/.local/share/jdtls/lombok.jar

# Find the current version in Gradle cache (if JAR is missing)
find ~/.gradle/caches -name "lombok-*.jar" | grep -v sources | grep -v plugin | sort -V | tail -1
```

### `workspaceSymbol` returns nothing after a new checkout or machine rebuild

The workspace cache is empty or stale. jdtls needs to run once to build the index.
Trigger it by opening any `.java` file via the LSP (e.g., a `hover` call), then wait
for the `build jobs finished` log message in the workspace log before retrying.

To watch the log live:
```bash
CACHE=$(python3 -c "import hashlib,os; print(f\"{os.environ['HOME']}/.cache/jdtls/jdtls-{hashlib.sha1(os.path.basename(os.getcwd()).encode()).hexdigest()}\")")
tail -f "$CACHE/.metadata/.log"
```

### `BundleException: Unable to acquire state change lock` in workspace log

WSL2 locking issue. Verify `-Dosgi.locking=none` is in the wrapper:
```bash
grep "locking" ~/.local/bin/jdtls
```
If missing, add it (see Change 2). Then clear the workspace cache and restart.

### `goToDefinition` on library code returns nothing

This requires the Gradle classpath container to be resolved (Buildship must start
cleanly). Check the workspace log for Buildship errors:
```bash
CACHE=$(python3 -c "import hashlib,os; print(f\"{os.environ['HOME']}/.cache/jdtls/jdtls-{hashlib.sha1(os.path.basename(os.getcwd()).encode()).hexdigest()}\")")
grep -i "buildship\|classpath\|error" "$CACHE/.metadata/.log" | tail -20
```

### How to wipe the workspace cache (safe, from project root)

```bash
rm -rf $(python3 -c "import hashlib,os; print(f\"{os.environ['HOME']}/.cache/jdtls/jdtls-{hashlib.sha1(os.path.basename(os.getcwd()).encode()).hexdigest()}\")")
```

jdtls will rebuild the index on next startup (takes ~30 seconds for a medium-sized project).

### How to upgrade jdtls

1. Download the new release and extract to `~/.local/share/jdtls/` (overwrite in place).
2. The wrapper at `~/.local/bin/jdtls` and `lombok.jar` survive because they are separate files.
3. Wipe the workspace cache (see above) — the index format may have changed.
4. Verify `hover` and `findReferences` still work after upgrade.
