---
title: Models and BYOK providers
---

# Models and BYOK providers

A model resource names the model that a service uses. It is a child of a runtime and starts
nothing on its own.

## Add a model

```cs
var copilot = builder.AddCopilot("copilot");
var chat = copilot.AddModel("chat", "gpt-5.4");

builder.AddProject<Projects.Api>("api").WithReference(chat).WaitFor(chat);
```

| Parameter | Description |
| --- | --- |
| `name` | The resource name. It is also the connection name and the prefix of the property variables (`CHAT_MODEL`, …). |
| `model` | The model identifier, for example `gpt-5.4`. |

A service that references `chat` receives the runtime's connection details plus the model:

```text
ConnectionStrings__chat=Endpoint=tcp://localhost:5000;ConnectionToken=<token>;Model=gpt-5.4
CHAT_HOST=localhost
CHAT_PORT=5000
CHAT_ENDPOINT=tcp://localhost:5000
CHAT_CONNECTIONTOKEN=<token>
CHAT_MODEL=gpt-5.4
```

The AppHost only passes the model name on. Each service applies it to the sessions it creates:
the .NET client reads it from the connection string, and other languages pass `CHAT_MODEL` to
their agent or session configuration. See the page for each language under *Connect services*.

Several models can share one runtime. Reference the one each service needs:

```cs
var chat = copilot.AddModel("chat", "gpt-5.4");
var fast = copilot.AddModel("fast", "gpt-5-mini");

builder.AddProject<Projects.Api>("api").WithReference(chat);
builder.AddNodeApp("web", "../web", "server.js").WithReference(fast);
```

## Reasoning effort

```cs
var chat = copilot.AddModel("chat", "gpt-5.4").WithReasoningEffort("high");
```

The effort is `low`, `medium`, `high`, or `xhigh`. Support depends on the model. Services that
reference the model receive:

```text
Copilot__ReasoningEffort=high
```

The .NET integration binds it to `Copilot:ReasoningEffort`. In other languages, read the variable
and pass it to the agent or session, for example `copilot.agents.<name>.reasoning-effort` in
Spring Boot. Services that reference only the runtime don't receive it.

## Bring-your-own-key providers

`WithProvider` serves the model through your own model endpoint instead of GitHub Copilot's
models. The provider is any Aspire resource with a connection string: a connection string
parameter, an Azure OpenAI or Foundry resource, or a local model server.

```cs
var openai = builder.AddConnectionString("openai");   // Endpoint=https://api.openai.com/v1;Key=sk-...
var chat = copilot.AddModel("chat", "gpt-5.4")
                  .WithProvider(openai, CopilotWireApi.Responses);
```

```cs
var ollama = builder.AddConnectionString("ollama");   // http://localhost:11434/v1
var local = copilot.AddModel("local", "llama3").WithProvider(ollama, type: "openai");
```

| Parameter | Description |
| --- | --- |
| `provider` | A resource with a connection string. |
| `wireApi` | `CopilotWireApi.Completions` or `CopilotWireApi.Responses`. The runtime default (`completions`) when omitted. |
| `type` | `openai`, `azure`, or `anthropic`. Inferred when omitted: `azure` for Azure OpenAI, Foundry, and Azure AI resources, otherwise `openai`. |

The base URL and the key are read from the provider resource:

| Value | Read from |
| --- | --- |
| Base URL | The `Uri` or `Endpoint` connection property; otherwise the `Endpoint`, `Uri`, or `Url` key of the connection string, or the whole connection string when it is a bare URL. |
| API key | The `Key` connection property; otherwise the `Key` or `ApiKey` key of the connection string. |

The provider settings are appended to the model's connection string:

```text
ConnectionStrings__chat=Endpoint=tcp://localhost:5000;ConnectionToken=<token>;Model=gpt-5.4;Provider=openai;ProviderEndpoint=https://api.openai.com/v1;ProviderKey=sk-...;WireApi=responses
```

| Key | Description |
| --- | --- |
| `Provider` | The provider type. |
| `ProviderEndpoint` | The base URL. |
| `ProviderKey` | The API key. Empty when the provider has none. |
| `WireApi` | `completions` or `responses`. Present only when set. |

> **Important:** the provider settings are only in the connection string. They are **not**
> emitted as `CHAT_*` properties. A service in another language that uses BYOK must parse
> `ConnectionStrings__chat` (semicolon-separated `key=value` pairs) and pass the values to its
> provider configuration: `copilot.providers.<name>.*` in Spring Boot,
> `quarkus.copilot.providers.<name>.*` in Quarkus, `provider: { type, baseUrl, apiKey, wireApi }`
> in Node.js, or `provider={"type": ..., "base_url": ..., "api_key": ..., "wire_api": ...}` in
> Python.

The provider endpoint and key go to the service, which sends them to the runtime when it creates a
session. Treat the model connection string as a secret.

Keyless providers that use Microsoft Entra ID aren't supported yet. The provider must accept an
API key, or no key at all.

### Health without a GitHub sign-in

The AppHost health check normally waits until the runtime is signed in to GitHub. When every model
of a runtime uses a provider, the runtime is healthy as soon as it answers, so a runtime that serves
only BYOK models needs no GitHub token.
