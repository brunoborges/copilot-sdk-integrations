# Quarkus museum and accessibility agents

Runnable single-command CLI using this repository's Quarkus extension,
declarative `@CopilotAgent` interfaces, ArC injection and generated tool metadata.
No HTTP endpoint or raw SDK client. Includes separate optional Wikipedia
research and deterministic HTML capstones.

Read the shared [workflow, safety boundaries and deviations](../java-common/README.md).
Requires JDK 21+ (JDK 25 is the common Java CI baseline).
Live agents require an authenticated Copilot CLI and model access; accessibility
also needs Playwright Chromium, and research needs Node.js 22+.

## Build and deterministic tests

From repository root:

```bash
./java/quarkus/mvnw -q -f java/quarkus/pom.xml \
  -pl deployment,test -am install -DskipTests
./java/quarkus/mvnw -q -f examples/java-common/pom.xml install
./java/quarkus/mvnw -q -f examples/quarkus/pom.xml verify
```

The first command installs core and the extension's deployment/runtime/test
artifacts. Default tests use the integration's fake runtime and require no
Copilot credentials, browser, Node, or external service. Initial Maven downloads
require connectivity; add `-o` once dependencies/plugins are cached.

## Run

```bash
cd examples/quarkus
java -jar target/quarkus-app/quarkus-run.jar museum apollo11
java -jar target/quarkus-app/quarkus-run.jar museum reef exhibit.html
java -jar target/quarkus-app/quarkus-run.jar museum terracotta
java -jar target/quarkus-app/quarkus-run.jar accessibility https://example.com/ report.html
java -jar target/quarkus-app/quarkus-run.jar research "Apollo 11"
# Put your manually educator-approved facts file in this working directory:
java -jar target/quarkus-app/quarkus-run.jar museum approved.json
```

Retain the **whole `target/quarkus-app/` directory**, not only the runner jar.
JSON goes to stdout, diagnostics to stderr. Output filenames are new `.html`
basenames in the current directory, never supplied to the model.
Structurally invalid output or fewer than three evidence-backed accessibility
findings produces an error rather than a report. No conformance certification.

## External configuration and prompts

Edit `src/main/resources/application.properties` or use Quarkus's native property,
environment, or JVM system-property overrides. Integration keys use the
**`quarkus.copilot` prefix**:

* `quarkus.copilot.agents.<museum|accessibility|research>.model` (optional; absent retains the runtime default)
* `quarkus.copilot.agents.<name>.response-timeout` (default `180s`)
* `quarkus.copilot.startup-timeout` (default `30s`)
* `workshop.browser.headless` (default `true`)
* `workshop.browser.timeout-ms` (default `30000`, positive milliseconds)

From this example directory:

```bash
java -Dquarkus.copilot.agents.museum.model=YOUR_MODEL_ID \
  -Dquarkus.copilot.agents.museum.response-timeout=120s \
  -jar target/quarkus-app/quarkus-run.jar museum apollo11
java -Dworkshop.browser.headless=false -Dworkshop.browser.timeout-ms=45000 \
  -jar target/quarkus-app/quarkus-run.jar accessibility https://example.com/
```

Durable prompts are shared classpath `.txt` resources referenced by each
declarative agent's `instructionsResource`, including in the built fast-jar
distribution. See [resource layout and fixed safeguards](../java-common/README.md#prompts-and-external-configuration).
Tool allowlists and permission handlers remain code-level invariants.

See [browser installation and the opt-in local capture test](../java-common/README.md#prerequisites-and-offline-tests).
