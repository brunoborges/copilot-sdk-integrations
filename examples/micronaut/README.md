# Micronaut museum and accessibility agents

Runnable single-command CLI using this repository's compile-time Micronaut
`@CopilotAgent` integration, injected proxies, named tools and permission beans.
No HTTP server or raw SDK client. Includes separate optional Wikipedia research
and deterministic HTML capstones.

Requires **JDK 25+** for this repository's Micronaut 5 integration.
This is a dependency requirement, not an example-specific language choice:
Micronaut core/inject **5.2.17**, the integration runtime, and its annotation
processor contain Java 25 class files (major version 69). Changing only this
example's compiler target to Java 21 would not make those dependencies runnable.
Live agents require an authenticated Copilot CLI and model access; accessibility
needs matching Playwright Chromium; research needs Node.js 22+.
Read the shared [workflow, safety boundaries and deviations](../java-common/README.md).

## Build and deterministic tests

From repository root:

```bash
./java/micronaut/mvnw -q -f java/micronaut/pom.xml \
  -pl runtime,processor,test -am install -DskipTests
./java/micronaut/mvnw -q -f examples/java-common/pom.xml install
./java/micronaut/mvnw -q -f examples/micronaut/pom.xml verify
```

The first command installs core, runtime, the agent annotation processor, and
the fake test runtime. Tests do not call Copilot, Node, a browser or Wikipedia.
Initial dependency downloads require connectivity; add `-o` after caching.
The compiler's processor path includes Micronaut's inject processor and this
repository's Copilot visitor. Shared tool metadata is generated in java-common.

## Run

From repository root:

```bash
./java/micronaut/mvnw -q -f examples/micronaut/pom.xml exec:java -Dexec.args="museum apollo11"
./java/micronaut/mvnw -q -f examples/micronaut/pom.xml exec:java -Dexec.args="museum reef exhibit.html"
./java/micronaut/mvnw -q -f examples/micronaut/pom.xml exec:java -Dexec.args="museum terracotta"
./java/micronaut/mvnw -q -f examples/micronaut/pom.xml exec:java \
  -Dexec.args="accessibility https://example.com/ report.html"
./java/micronaut/mvnw -q -f examples/micronaut/pom.xml exec:java \
  '-Dexec.args=research "Apollo 11"'
# Put your manually approved facts file in the process working directory:
./java/micronaut/mvnw -q -f examples/micronaut/pom.xml exec:java -Dexec.args="museum approved.json"
```

These commands use the repository root as working directory, so approved files
are read and HTML reports written there. To work entirely within this example,
`cd examples/micronaut` then use `../../java/micronaut/mvnw ...` instead.
The application runs inside the exec plugin with framework-managed lifecycle.
JSON is printed to stdout and diagnostics to stderr. Invalid agent output is
rejected; snapshot evidence alone never certifies accessibility conformance.

## External configuration and prompts

`src/main/resources/application.properties` supplies defaults. Use Micronaut's
native property, environment, and JVM system-property overrides:

* `micronaut.copilot.agents.<museum|accessibility|research>.model` (optional; absent retains the runtime default)
* `micronaut.copilot.agents.<name>.response-timeout` (default `180s`)
* `micronaut.copilot.startup-timeout` (default `30s`)
* `workshop.browser.headless` (default `true`)
* `workshop.browser.timeout-ms` (default `30000`, positive milliseconds)

For exec-plugin launches, JVM system properties are set before Maven starts:

```bash
MAVEN_OPTS="-Dmicronaut.copilot.agents.museum.model=YOUR_MODEL_ID -Dmicronaut.copilot.agents.museum.response-timeout=120s" \
  ./java/micronaut/mvnw -q -f examples/micronaut/pom.xml exec:java -Dexec.args="museum apollo11"
MAVEN_OPTS="-Dworkshop.browser.headless=false -Dworkshop.browser.timeout-ms=45000" \
  ./java/micronaut/mvnw -q -f examples/micronaut/pom.xml exec:java \
  -Dexec.args="accessibility https://example.com/"
```

Durable prompts live in shared classpath `.txt` resources, loaded by the
integration through `@CopilotAgent.instructionsResource` from the packaged
java-common dependency. No external working-directory prompt files are required.
See [resource layout and fixed safeguards](../java-common/README.md#prompts-and-external-configuration).
Tool permissions, URL scope, and file validation remain strict in code.

See [browser installation and the opt-in local capture test](../java-common/README.md#prerequisites-and-offline-tests).
