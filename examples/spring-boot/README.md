# Spring Boot museum and accessibility agents

Runnable single-command CLI using this repository's Spring Boot starter,
declarative `@CopilotAgent` proxies, and DI-managed tool/permission beans.
No HTTP server or raw SDK client is created. Includes optional separate
Wikipedia research and deterministic HTML capstones.

Read the shared [workflow, safety boundaries and deviations](../java-common/README.md)
before running. JDK 21+ is required (JDK 25 works for all Java examples).
Live runs additionally require an authenticated Copilot CLI and model access.
Accessibility requires matching Playwright Chromium; research requires Node 22+.

## Build and deterministic tests

From repository root:

```bash
./java/spring-boot/mvnw -q -f java/spring-boot/pom.xml \
  -pl copilot-spring-boot-starter,copilot-spring-boot-test -am install -DskipTests
./java/spring-boot/mvnw -q -f examples/java-common/pom.xml install
./java/spring-boot/mvnw -q -f examples/spring-boot/pom.xml verify
```

The default tests use the integration's fake runtime and require no Copilot,
browser, Node, network service, or credentials. Maven initially needs dependency
downloads; after caching, add `-o` for offline tests.

## Run

```bash
cd examples/spring-boot
java -jar target/workshop-spring-boot-0.1.0-SNAPSHOT.jar museum apollo11
java -jar target/workshop-spring-boot-0.1.0-SNAPSHOT.jar museum reef exhibit.html
java -jar target/workshop-spring-boot-0.1.0-SNAPSHOT.jar museum terracotta
java -jar target/workshop-spring-boot-0.1.0-SNAPSHOT.jar accessibility https://example.com/ report.html
java -jar target/workshop-spring-boot-0.1.0-SNAPSHOT.jar research "Apollo 11"
# For externally checked facts, place approved.json in this directory:
java -jar target/workshop-spring-boot-0.1.0-SNAPSHOT.jar museum approved.json
```

Or use the repository wrapper from root:

```bash
./java/spring-boot/mvnw -q -f examples/spring-boot/pom.xml exec:java \
  -Dexec.args="museum apollo11"
```

The working directory is the process's directory (repository root in the last
command). JSON goes to stdout; diagnostics and human-review disclaimers go to
stderr. Only validated museum/accessibility results can produce HTML; a website
with fewer than three supported findings is deliberately rejected.

## External configuration and prompts

`src/main/resources/application.properties` supplies operational defaults:
`copilot.agents.<museum|accessibility|research>.response-timeout=180s`,
`copilot.startup-timeout=30s`, `workshop.browser.headless=true`, and
`workshop.browser.timeout-ms=30000`. Optional per-agent
`copilot.agents.<name>.model` uses any model available to your Copilot account;
leaving it absent retains the runtime default. Override using Spring Boot's
normal properties, environment, or JVM system-property mechanisms, for example:

```bash
java -Dcopilot.agents.museum.model=YOUR_MODEL_ID \
  -Dcopilot.agents.museum.response-timeout=120s \
  -jar target/workshop-spring-boot-0.1.0-SNAPSHOT.jar museum apollo11
java -Dworkshop.browser.headless=false -Dworkshop.browser.timeout-ms=45000 \
  -jar target/workshop-spring-boot-0.1.0-SNAPSHOT.jar accessibility https://example.com/
```

These JVM commands assume `cd examples/spring-boot`. Durable prompts are shared
classpath `.txt` files referenced by `@CopilotAgent.instructionsResource`, not
Java text blocks. See [resource layout and invariants](../java-common/README.md#prompts-and-external-configuration).
Safety/tool permissions are not application tuning settings.

Browser installation and the optional local-browser fixture test are documented
in [java-common](../java-common/README.md#prerequisites-and-offline-tests).
