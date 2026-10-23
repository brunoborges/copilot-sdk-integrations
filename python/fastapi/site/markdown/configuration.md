---
title: Configuration
---

# Configuration

`CopilotSettings` is a
[pydantic-settings](https://docs.pydantic.dev/latest/concepts/pydantic_settings/) model. It
reads `COPILOT_*` environment variables and `.env`, with `__` as the nesting delimiter. Every
field is optional. Unknown keys inside nested sections are rejected.

```sh
COPILOT_RUNTIME__CONNECTION=stdio          # stdio | uri | tcp | inprocess
COPILOT_RUNTIME__URL=http://copilot:8080   # for "uri": an already-running runtime
COPILOT_RUNTIME__CONNECTION_TOKEN=...      # for "uri" / "tcp"
COPILOT_RUNTIME__CLI_PATH=...              # default: COPILOT_CLI_PATH, then the downloaded runtime
COPILOT_RUNTIME__PORT=0                    # for "tcp"
COPILOT_RUNTIME__BASE_DIRECTORY=...        # default: ./.copilot
COPILOT_RUNTIME__LOG_LEVEL=info
COPILOT_RUNTIME__START=lazy                # lazy | eager
COPILOT_RUNTIME__REQUEST_TIMEOUT=300       # seconds per agent call
COPILOT_AUTH__GITHUB_TOKEN=...             # unset: the runtime's signed-in user
COPILOT_AUTH__TOKEN_RESOLVER=app.auth.GitHubTokens
COPILOT_DEFAULTS__MODEL=gpt-5
COPILOT_DEFAULTS__REASONING_EFFORT=medium
COPILOT_DEFAULTS__PERMISSIONS=deny
COPILOT_DEFAULTS__WORKING_DIRECTORY=isolated
COPILOT_MCP_SERVERS='{"docs": {"type": "http", "url": "https://example.com/mcp"}}'
COPILOT_PROVIDERS='{"azure": {"type": "azure", "base_url": "https://contoso.openai.azure.com"}}'
COPILOT_AGENTS='{"support": {"model": "claude-sonnet-4.5", "timeout": 60}}'
COPILOT_HOOKS='["app.hooks.AuditHooks"]'
COPILOT_VALIDATE_ON_STARTUP=true
```

You can also build settings in code and pass them in:

```python
from copilot_fastapi import Copilot, CopilotSettings

copilot = Copilot(CopilotSettings(defaults={"model": "gpt-5"}), agents=[SupportAgent])
```

Tokens and connection tokens are `SecretStr` values, so they never appear in `repr` or logs.

## Runtime connection

| `connection` | Behavior |
|---|---|
| `stdio` | Each worker process starts its own runtime subprocess. This is the default and the simplest option. |
| `uri` | Connect to a runtime that is already running at `url`, for example a sidecar container. |
| `tcp` | Start a runtime that listens on `port`. |
| `inprocess` | Load the runtime as a library in the application process. |

The runtime starts on the first agent call (`start=lazy`) or in the app's lifespan
(`start=eager`). It runs on its own event-loop thread and shuts down when the lifespan ends.

## MCP servers and providers

Define MCP servers and BYOK providers once, then refer to them by name from agents:

```sh
COPILOT_MCP_SERVERS='{"docs": {"type": "http", "url": "https://docs.example.com/mcp"}}'
COPILOT_PROVIDERS='{"azure": {"type": "azure", "base_url": "https://contoso.openai.azure.com"}}'
```

```python
class DocsAgent(CopilotAgent):
    mcp_servers = ["docs"]
    provider = "azure"
```

An agent can also pass a mapping of server configs or a provider mapping directly.

## Per-agent overrides

`agents` overrides any agent attribute without changing code. Keys are the attribute names:

```sh
COPILOT_AGENTS='{"support": {"model": "claude-sonnet-4.5", "timeout": 120}}'
```

Precedence is `agents` overrides, then class attributes, then `defaults`. Overrides for agents
that are not registered produce a startup warning.
