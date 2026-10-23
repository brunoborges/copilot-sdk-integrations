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
