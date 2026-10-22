---
title: Python
---

# Python

Python services connect to the AppHost's runtime with the
[Copilot SDK for Python](https://github.com/github/copilot-sdk/tree/main/python)
(`github-copilot-sdk`). This repository has no Python framework integration, so the service uses the
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
pip install github-copilot-sdk
```

The examples on this page were checked against version `1.0.26`.

## Connect to the runtime

`RuntimeConnection.for_uri` connects to a runtime that is already running. It accepts a port,
`host:port`, or a full `http(s)://` URL. Build the address from `CHAT_HOST` and `CHAT_PORT`, not from
`CHAT_ENDPOINT`, whose scheme is `tcp://`:

```python
import os

from copilot import CopilotClient, RuntimeConnection
from copilot.session import PermissionHandler


def copilot_client() -> CopilotClient:
    return CopilotClient(
        connection=RuntimeConnection.for_uri(
            f"{os.environ['CHAT_HOST']}:{os.environ['CHAT_PORT']}",
            connection_token=os.environ["CHAT_CONNECTIONTOKEN"],
        )
    )


async def ask(question: str) -> str | None:
    async with copilot_client() as client:
        session = await client.create_session(
            model=os.environ["CHAT_MODEL"],
            on_permission_request=PermissionHandler.approve_all,
        )
        reply = await session.send_and_wait(question, timeout=60.0)
        return reply.data.content if reply else None
```

`connection_token` is a keyword-only argument. Don't pass `github_token` or `use_logged_in_user` to
the client: the SDK rejects them for a runtime it connects to, because that runtime manages its own
sign-in.

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
    connection = RuntimeConnection.for_uri(
        f"{os.environ['CHAT_HOST']}:{os.environ['CHAT_PORT']}",
        connection_token=os.environ["CHAT_CONNECTIONTOKEN"],
    )
    async with CopilotClient(connection=connection) as client:
        app.state.copilot = client
        yield


app = FastAPI(lifespan=lifespan)


@app.post("/ask")
async def ask(question: str) -> dict:
    session = await app.state.copilot.create_session(
        model=os.environ["CHAT_MODEL"],
        reasoning_effort=os.environ.get("Copilot__ReasoningEffort"),
        on_permission_request=PermissionHandler.approve_all,
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
    mcp_servers={
        "docs": {
            "type": "http",
            "url": os.environ["Copilot__McpServers__docs__Url"],
            "tools": ["search_docs", "get_page"],
        },
    },
    on_permission_request=PermissionHandler.approve_all,
)
```

See [MCP servers](mcp-servers.md).

## BYOK providers

Provider settings are only in `ConnectionStrings__chat`. Parse it and pass the values as the
session's `provider`:

```python
parts = dict(
    pair.split("=", 1)
    for pair in os.environ["ConnectionStrings__chat"].split(";")
    if pair
)

provider = None
if "Provider" in parts:
    provider = {"type": parts["Provider"], "base_url": parts["ProviderEndpoint"]}
    if parts.get("ProviderKey"):
        provider["api_key"] = parts["ProviderKey"]
    if "WireApi" in parts:
        provider["wire_api"] = parts["WireApi"]

session = await client.create_session(
    model=parts["Model"],
    provider=provider,
    on_permission_request=PermissionHandler.approve_all,
)
```

See [Models and BYOK providers](models.md#bring-your-own-key-providers).

## Telemetry

The runtime exports its own traces to the Aspire dashboard. Your service exports its spans with
the OpenTelemetry SDK for Python, as any Python app under Aspire does. The Copilot SDK for Python
can propagate W3C trace context to the runtime so the two connect; install it with the `telemetry`
extra (`pip install "github-copilot-sdk[telemetry]"`) and see the SDK's README for the `telemetry`
options. See also [Telemetry and health](telemetry-and-health.md).
