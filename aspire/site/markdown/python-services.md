---
title: Python
---

# Python

Python services connect to the AppHost's runtime with the
[Microsoft Copilot SDK for Python](https://github.com/microsoft/copilot-sdk/tree/main/python)
(`microsoft-copilot-sdk`, imported as `copilot`). This repository has no Python framework integration, so the service uses the
SDK directly.

## AppHost

The [Aspire Python hosting integration](https://aspire.dev/integrations/frameworks/python/)
(`Aspire.Hosting.Python`) runs scripts, modules, and ASGI apps:

```bash
aspire add python
```

```cs
var copilot = builder.AddCopilot("copilot");
var chat = copilot.AddModel("chat", "gpt-5.4");

builder.AddUvicornApp("agent", "../agent", "main:app")   // FastAPI or another ASGI app
       .WithReference(chat)
       .WaitFor(chat);

builder.AddPythonApp("worker", "../worker", "worker.py")  // a plain script
       .WithReference(chat)
       .WaitFor(chat);
```

Both apps receive `CHAT_HOST`, `CHAT_PORT`, `CHAT_CONNECTIONTOKEN`, and `CHAT_MODEL`, among others.
See the [Environment variable reference](environment-variables.md).

## Install the SDK

```bash
pip install microsoft-copilot-sdk
```

The examples on this page were checked against version `2.0.1`.

## Connect to the runtime

`RuntimeConnection.for_tcp` connects to a runtime that is already running. It takes the host and the
port as separate arguments, so pass `CHAT_HOST` and `CHAT_PORT` (not `CHAT_ENDPOINT`, which is a
`tcp://` URL):

```python
import os

from copilot import CopilotClient, RuntimeConnection
from copilot.session import PermissionHandler


def copilot_client() -> CopilotClient:
    return CopilotClient(
        mode="empty",
        runtime=RuntimeConnection.for_tcp(
            os.environ["CHAT_HOST"],
            int(os.environ["CHAT_PORT"]),
            connection_token=os.environ["CHAT_CONNECTIONTOKEN"],
        ),
    )


async def ask(question: str) -> str | None:
    async with copilot_client() as client:
        session = await client.create_session(
            model=os.environ["CHAT_MODEL"],
            tools={"available_tools": []},
            permissions={"on_request": PermissionHandler.approve_all},
        )
        reply = await session.send_and_wait(question, timeout=60.0)
        return reply.data.content if reply else None
```

`connection_token` is a keyword-only argument. Don't pass `github={"token": ...}` or
`github={"use_logged_in_user": ...}` to the client: the SDK rejects them for a runtime it connects to,
because that runtime manages its own sign-in.

`mode` is required. `"empty"` suits a service that shares the runtime with other services: sessions
get no built-in tools, and each session must list the tools it allows in `tools.available_tools`
(the names of your own tools as `custom:<name>`, `mcp:*` for MCP tools, or `[]` for none). Use
`mode="coding-agent"` for the CLI's built-in tools, which run with the runtime's access to its host.

`PermissionHandler.approve_all` approves every tool call, which suits a demo. In a real service,
pass a handler that decides per request. Without a handler, permission requests stay pending.

## A FastAPI service

For a long-running service, create one client at startup and share it:

```python
import os
from contextlib import asynccontextmanager

from fastapi import FastAPI
from copilot import CopilotClient, RuntimeConnection
from copilot.session import PermissionHandler


@asynccontextmanager
async def lifespan(app: FastAPI):
    runtime = RuntimeConnection.for_tcp(
        os.environ["CHAT_HOST"],
        int(os.environ["CHAT_PORT"]),
        connection_token=os.environ["CHAT_CONNECTIONTOKEN"],
    )
    async with CopilotClient(mode="empty", runtime=runtime) as client:
        app.state.copilot = client
        yield


app = FastAPI(lifespan=lifespan)


@app.post("/ask")
async def ask(question: str) -> dict:
    session = await app.state.copilot.create_session(
        model=os.environ["CHAT_MODEL"],
        inference={"reasoning_effort": os.environ.get("Copilot__ReasoningEffort")},
        tools={"available_tools": []},
        permissions={"on_request": PermissionHandler.approve_all},
    )
    reply = await session.send_and_wait(question, timeout=60.0)
    return {"answer": reply.data.content if reply else None}
```

Run it from the AppHost with `AddUvicornApp("agent", "../agent", "main:app")`.

## MCP servers

When the AppHost calls `WithMcpServer`, pass the injected URL to the session:

```python
session = await client.create_session(
    model=os.environ["CHAT_MODEL"],
    mcp={
        "servers": {
            "docs": {
                "type": "http",
                "url": os.environ["Copilot__McpServers__docs__Url"],
                "tools": ["search_docs", "get_page"],
            },
        },
    },
    tools={"available_tools": ["mcp:*"]},
    permissions={"on_request": PermissionHandler.approve_all},
)
```

In `empty` mode, `mcp:*` in `available_tools` lets the session use MCP tools; each server's `tools`
list still limits which of its tools are offered.

See [MCP servers](mcp-servers.md).

## BYOK providers

Provider settings are only in `ConnectionStrings__chat`. Parse it, register the values as a named
provider, and make that provider the session's default:

```python
parts = dict(
    pair.split("=", 1)
    for pair in os.environ["ConnectionStrings__chat"].split(";")
    if pair
)

inference = None
github = None
if "Provider" in parts:
    provider = {"name": "byok", "type": parts["Provider"], "base_url": parts["ProviderEndpoint"]}
    if parts.get("ProviderKey"):
        provider["api_key"] = parts["ProviderKey"]
    if "WireApi" in parts:
        provider["wire_api"] = parts["WireApi"]
    inference = {"default_provider": "byok", "providers": [provider]}
    github = {"capi": {"enabled": False}}  # serve only the provider's models

session = await client.create_session(
    model=parts["Model"],
    inference=inference,
    github=github,
    tools={"available_tools": []},
    permissions={"on_request": PermissionHandler.approve_all},
)
```

See [Models and BYOK providers](models.md#bring-your-own-key-providers).

## Telemetry

The runtime exports its own traces to the Aspire dashboard. Your service exports its spans with
the OpenTelemetry SDK for Python, as any Python app under Aspire does. The Microsoft Copilot SDK for
Python can propagate W3C trace context to the runtime so the two connect; install it with the
`telemetry` extra (`pip install "microsoft-copilot-sdk[telemetry]"`) and see the SDK's README for its
telemetry options. See also [Telemetry and health](telemetry-and-health.md).
