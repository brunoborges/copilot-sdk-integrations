---
title: Checks and commands
---

# Checks and commands

## System checks

`manage.py check` (and `runserver`, `migrate`, ...) builds every agent definition and reports:

| ID | Problem |
|---|---|
| `copilot.E001` | Invalid `COPILOT` settings or an invalid agent attribute. |
| `copilot.E002` | An agent references an unknown tool, MCP server, provider, hook, or token resolver. |
| `copilot.E003` | Tool filters conflict, for example `available_tools` that would hide the agent's own tools. |
| `copilot.E004` | A bring-your-own-key provider without a `model`. |
| `copilot.E005` | Two agents or two tools share a name. |
| `copilot.W001` | The runtime hasn't been downloaded, so the first call will download it. |
| `copilot.W002` | An agent uses `allow_all()`. |
| `copilot.W003` | Many stdio runtimes: `WEB_CONCURRENCY` is above 2 with `CONNECTION: "stdio"`. Consider a shared runtime with `CONNECTION: "uri"`. |

## Management commands

### `copilot_download_runtime`

Downloads the Copilot runtime the SDK uses. Run it when building images so the first request
doesn't download it.

```sh
python manage.py copilot_download_runtime
python manage.py copilot_download_runtime --force --runtime-version 1.0.30
python manage.py copilot_download_runtime --in-process      # also the in-process library
```

The option is `--runtime-version` because Django reserves `--version` for every command.

### `copilot_check`

Prints the effective configuration, with secrets redacted, and each agent's model, tools, MCP
servers, permission policy, hooks, and identity. It then starts the runtime and reports its authentication
status and available models.

```sh
python manage.py copilot_check
python manage.py copilot_check --no-runtime        # configuration only
python manage.py copilot_check --timeout 30
```

## Deployment notes

- **Gunicorn and uWSGI**: the runtime is started lazily in each worker and is safe across
  `fork()`. With `--preload`, a runtime started in the master is discarded in the children.
- **Many workers**: each worker with `CONNECTION: "stdio"` runs its own runtime process. For
  larger deployments, run one runtime as a sidecar and use `CONNECTION: "uri"`.
- **ASGI**: use the `a`-prefixed methods in async views. Sync methods raise an error inside a
  running event loop rather than blocking it.
- **Logging**: loggers are under `copilot_agents` (for example `copilot_agents.tools`). Denied permission
  requests are logged at `INFO`.
