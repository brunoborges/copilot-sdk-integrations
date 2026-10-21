# copilot-spring-boot

Spring Boot starter foundation for the Copilot SDK for Java, targeting
**Spring Boot 4.1.1** and **Java 21**.

## Status

This repository is a buildable scaffold only. It does not yet include API design,
Java implementation, auto-configuration registrations, or a Copilot SDK dependency
or integration. No artifacts have been published.

## Project structure

| Module | Purpose |
| --- | --- |
| `copilot-spring-boot-parent` | Root Maven parent and reactor with shared dependency and plugin management. |
| `copilot-spring-boot-autoconfigure` | Empty library JAR reserved for future auto-configuration. |
| `copilot-spring-boot-starter` | Dependency-only JAR combining the auto-configuration module and Spring Boot starter. |

Both child modules are ordinary library JARs, not executable applications.
Maven coordinates use the group `io.github.brunoborges` and version
`0.1.0-SNAPSHOT`.

## Building

Install JDK 21 and set `JAVA_HOME` to its installation directory. A separate Maven
installation is not required: the committed Maven Wrapper uses Maven 3.9.15.
The first build requires network access to download Maven and dependencies.

On macOS or Linux:

```sh
./mvnw --batch-mode --no-transfer-progress verify
```

On Windows:

```bat
mvnw.cmd --batch-mode --no-transfer-progress verify
```

The build verifies the Maven reactor and packages the intentionally empty modules;
there are no application tests yet. GitHub Actions runs the same build on Java 21
for pushes and pull requests.

## License

[MIT](LICENSE).
