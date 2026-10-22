---
title: Example application
---

<a id="example-application"></a>

# Example application

Build:

```sh
./mvnw --batch-mode --no-transfer-progress -pl copilot-spring-boot-example -am package
```

Display help without opening sessions:

```sh
java -jar copilot-spring-boot-example/target/copilot-spring-boot-example-0.1.0-SNAPSHOT.jar --help
```

| Mode | Behavior | Top-level prompt submissions |
| --- | --- | --- |
| No mode option | Two concurrent raw SDK sessions with separately attached listeners. | Two |
| `--agent` | Two typed-agent conversations, then a follow-up resuming the first. | Three |
| `--stream` | Two independent typed reactive event streams. | Two |
| `--instance` | Two generated bound instances, closed, then a fresh handle strictly resuming the first for a follow-up. | Three |

For a real run, pass `--copilot.cli-path=/absolute/path/to/copilot` or the
supported external-server configuration and authenticate appropriately.
`--agent`, `--stream`, and `--instance` are mutually exclusive. Supply either zero positional
prompts for defaults or exactly two quoted prompts.

The example includes `ReferenceTools`, explicit `DenyPermissions`, a shared
session-context-aware output listener, and a customized system-message bean.
Actuator activates observations, but the application stays non-web with no
HTTP endpoints. Stream mode logs event types; the configured listener logs
final assistant text. These counts describe application prompt submissions,
not a guaranteed number of underlying model calls: the runtime can perform
additional model/tool iterations. Running these modes consumes real Copilot usage.

---

**Previous:** [Extension SPIs](extensions.html)

**Next:** [Security and troubleshooting](operations.html)
