---
title: Configuration
---

# Configuration

All settings live in one `COPILOT` dictionary. Every key is optional; missing keys take the
defaults below. Unknown keys are reported as `copilot.E001` by `manage.py check`.

```python
COPILOT = {
    "RUNTIME": {
        "CONNECTION": "stdio",        # stdio | uri | tcp | inprocess
        "URL": None,                  # for "uri": an already-running runtime
        "CONNECTION_TOKEN": None,     # for "uri" / "tcp"
        "CLI_PATH": None,             # default: COPILOT_CLI_PATH, then the downloaded runtime
        "PORT": 0,                    # for "tcp"
        "BASE_DIRECTORY": None,       # default: BASE_DIR / ".copilot"
        "LOG_LEVEL": "info",
        "START": "lazy",              # lazy | eager
        "REQUEST_TIMEOUT": 300,       # seconds per agent call
        "MODE": "copilot-cli",
    },
    "AUTH": {
        "GITHUB_TOKEN": None,         # None: the runtime's signed-in user
        "TOKEN_RESOLVER": None,       # dotted path to a token resolver
    },
    "DEFAULTS": {
        "MODEL": None,
        "REASONING_EFFORT": None,
        "PERMISSIONS": "deny",
        "WORKING_DIRECTORY": "isolated",
    },
    "MCP_SERVERS": {},                # name -> SDK MCP server config
    "PROVIDERS": {},                  # name -> BYOK provider config
    "AGENTS": {},                     # agent name -> attribute overrides
    "HOOKS": [],                      # dotted paths to CopilotAgentHooks for every agent
    "SESSIONS": {
        "MAX_AGE": None,              # seconds or timedelta; None: no cleanup
        "CLEANUP_INTERVAL": None,     # default: MAX_AGE / 2, between 1 minute and 1 hour
        "BACKGROUND_CLEANUP": True,
    },
    "TELEMETRY": None,                # SDK telemetry config
}
```

## Runtime connection

| `CONNECTION` | Behavior |
|---|---|
| `stdio` | Each Django process starts its own runtime subprocess. This is the default and the simplest option. |
| `uri` | Connect to a runtime that is already running at `URL`, for example a sidecar container. |
| `tcp` | Start a runtime that listens on `PORT`. |
| `inprocess` | Load the runtime as a library in the Django process. |

The runtime starts on the first agent call (`START: "lazy"`) or when Django starts
(`"eager"`). It runs on its own event-loop thread, survives `fork()` (for example under
Gunicorn `--preload`), and shuts down when settings change in tests.

## MCP servers and providers

Define MCP servers and BYOK providers once, then refer to them by name from agents:

```python
COPILOT = {
    "MCP_SERVERS": {
        "docs": {"type": "http", "url": "https://docs.example.com/mcp",
                 "headers": {"Authorization": f"Bearer {DOCS_TOKEN}"}},
    },
    "PROVIDERS": {
        "azure": {"type": "azure", "base_url": "https://contoso.openai.azure.com",
                  "api_key": os.environ["AZURE_OPENAI_KEY"]},
    },
}

class DocsAgent(CopilotAgent):
    mcp_servers = ["docs"]
    provider = "azure"
```

`copilot_check` redacts secrets such as tokens, API keys, and authorization headers.

## Per-agent overrides

`AGENTS` overrides any agent attribute without changing code. Keys are the upper-case
attribute names:

```python
COPILOT = {
    "AGENTS": {
        "support": {"MODEL": "claude-sonnet-4.5", "TIMEOUT": 120},
        "github_triage": {"TOOL_SEARCH": {"enabled": True, "defer_threshold": 50}},
    },
}
```

Precedence is `AGENTS` overrides, then class attributes, then `DEFAULTS`.

## Session cleanup

Session cleanup is opt-in because deleting sessions is destructive. With `MAX_AGE` set, each
process sweeps every `CLEANUP_INTERVAL` from a daemon thread started in `AppConfig.ready()`
(and restarted after `fork()`). A sweep deletes only sessions that are not remote, whose ID
starts with `agent-`, and whose last modification is older than `MAX_AGE`. It re-reads the
metadata right before deleting, skips sessions with a turn in flight, and does nothing until
the runtime has been started by a request. Errors are logged and retried on the next sweep.

```python
COPILOT = {"SESSIONS": {"MAX_AGE": timedelta(days=30)}}
```

To sweep from one place instead of every worker, set `"BACKGROUND_CLEANUP": False` and
schedule [`copilot_cleanup_sessions`](operations.md#copilotcleanupsessions) with cron or
your task scheduler.
