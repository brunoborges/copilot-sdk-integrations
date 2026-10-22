---
title: How it works
---

# How it works

The integration has two halves. The AppHost half is C# and runs, secures, and describes the
Copilot runtime. The service half is whatever each service uses to talk to the runtime. The two
meet in the environment variables that Aspire injects through `WithReference`.

<figure class="arch">
<svg viewBox="0 0 860 362" role="img" aria-labelledby="hiw-title hiw-desc" xmlns="http://www.w3.org/2000/svg">
<title id="hiw-title">AppHost resources and the services that connect to them</title>
<desc id="hiw-desc">In the C# AppHost, AddCopilot adds the runtime process or container (headless, TCP, connection token), an MCP server and the chat model, which has a reasoning effort and a provider. WithReference(chat) injects CHAT environment variables into services written in any language: .NET uses AddCopilotClient, Java uses copilot.cli-url or quarkus.copilot.cli-url, Node.js uses createCopilot and Python uses RuntimeConnection.for_uri.</desc>
<defs>
<marker id="hiw-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path class="arch-head" d="M0 0 10 5 0 10z"/></marker>
</defs>
<rect class="arch-frame" x="10" y="10" width="380" height="340" rx="14"/>
<text class="arch-caption" x="30" y="36">AppHost · C#</text>
<path class="arch-link" d="M50 92V236M50 131H70M50 186H70M50 236H70M90 254V318M90 280H110M90 318H110"/>
<rect class="arch-node" x="30" y="56" width="200" height="36" rx="8"/>
<text class="arch-code" x="130" y="79" text-anchor="middle">AddCopilot("copilot")</text>
<rect class="arch-runtime" x="70" y="108" width="300" height="46" rx="8"/>
<text class="arch-title" x="86" y="127" style="font-size:13px">runtime process or container</text>
<text class="arch-sub" x="86" y="145">--headless · TCP · connection token</text>
<rect class="arch-node" x="70" y="170" width="200" height="32" rx="8"/>
<text class="arch-code" x="86" y="191">WithMcpServer(docs)</text>
<rect class="arch-node" x="70" y="218" width="230" height="36" rx="8"/>
<text class="arch-code" x="86" y="241">AddModel("chat", "gpt-5.4")</text>
<rect class="arch-node" x="110" y="266" width="220" height="28" rx="7"/>
<text class="arch-code-sm" x="124" y="284">WithReasoningEffort("high")</text>
<rect class="arch-node" x="110" y="304" width="220" height="28" rx="7"/>
<text class="arch-code-sm" x="124" y="322">WithProvider(openai)</text>
<path class="arch-link" d="M300 236H320M470 236H488" marker-end="url(#hiw-arrow)"/>
<rect class="arch-pill" x="320" y="214" width="150" height="44" rx="10"/>
<text class="arch-pill-text" x="395" y="232" text-anchor="middle">WithReference(chat)</text>
<text class="arch-sub" x="395" y="249" text-anchor="middle" style="font-size:11px">injects CHAT_* env vars</text>
<rect class="arch-frame" x="490" y="10" width="360" height="340" rx="14"/>
<text class="arch-caption" x="510" y="36">Services · any language</text>
<rect class="arch-node" x="506" y="56" width="328" height="60" rx="10"/>
<text class="arch-title" x="522" y="81" style="font-size:14px">.NET</text>
<text class="arch-code-sm" x="522" y="101">AddCopilotClient("chat")</text>
<rect class="arch-node" x="506" y="128" width="328" height="60" rx="10"/>
<text class="arch-title" x="522" y="153" style="font-size:14px">Java</text>
<text class="arch-code-sm" x="522" y="173">copilot.cli-url / quarkus.copilot.cli-url</text>
<rect class="arch-node" x="506" y="200" width="328" height="60" rx="10"/>
<text class="arch-title" x="522" y="225" style="font-size:14px">Node.js</text>
<text class="arch-code-sm" x="522" y="245">createCopilot({ cliUrl, configureClient })</text>
<rect class="arch-node" x="506" y="272" width="328" height="60" rx="10"/>
<text class="arch-title" x="522" y="297" style="font-size:14px">Python</text>
<text class="arch-code-sm" x="522" y="317">RuntimeConnection.for_uri(...)</text>
</svg>
</figure>

## The runtime resource

`AddCopilot("copilot")` adds a `CopilotRuntimeResource`. It is the same headless Copilot
runtime that every Copilot SDK starts for you when you don't point it elsewhere. Under Aspire it
runs once, as a resource, and services connect to it over TCP instead of starting their own.

The runtime:

- runs with `--headless --no-auto-update` and listens on the resource's `tcp` endpoint;
- requires a connection token (`COPILOT_CONNECTION_TOKEN`), generated as the secret parameter
  `copilot-connection-token`;
- signs in to GitHub with the developer's signed-in user, or with a token from
  `WithGitHubToken`;
- exports traces, metrics, and logs to the Aspire dashboard;
- reports health through an AppHost health check.

It can run as a local executable (the default when you run the AppHost), a container (the
default when you publish), or a runtime that Aspire doesn't manage. See
[Runtime resource](runtime.md).

## Model resources

`AddModel("chat", "gpt-5.4")` adds a `CopilotModelResource` that is a child of the runtime. A
model resource doesn't start anything. It is a named connection: the runtime connection plus a
model identifier, and optionally a reasoning effort and a bring-your-own-key provider. One runtime
can serve several models:

```cs
var copilot = builder.AddCopilot("copilot");
var chat = copilot.AddModel("chat", "gpt-5.4");
var fast = copilot.AddModel("fast", "gpt-5-mini");
```

See [Models and BYOK providers](models.md).

## What `WithReference` injects

A service can reference the runtime (`WithReference(copilot)`) or a model
(`WithReference(chat)`). Aspire then sets three groups of environment variables on it.

**A connection string**, in the format that the .NET integration parses:

```text
ConnectionStrings__chat=Endpoint=tcp://localhost:5000;ConnectionToken=<token>;Model=gpt-5.4
```

**Connection properties**, one variable per property. The name is the resource name in upper
case, an underscore, and the property name:

```text
CHAT_HOST=localhost
CHAT_PORT=5000
CHAT_ENDPOINT=tcp://localhost:5000
CHAT_CONNECTIONTOKEN=<token>
CHAT_MODEL=gpt-5.4
```

These are the variables that Java, Node.js, and Python services read. They don't need to parse
the connection string.

**Copilot settings**, when the AppHost configures MCP servers or a reasoning effort:

```text
Copilot__McpServers__docs__Type=http
Copilot__McpServers__docs__Url=http://localhost:7000/mcp
Copilot__McpServers__docs__Tools__0=search_docs
Copilot__ReasoningEffort=high
```

These names follow the .NET configuration convention (`__` separates sections), so the .NET
integration binds them to its `Copilot` options with no code. Services in other languages can
read them as plain environment variables if they want to apply the same settings.

The complete list is in the [Environment variable reference](environment-variables.md).

## Who owns what

| Concern | Owned by |
| --- | --- |
| Starting and stopping the runtime | The AppHost |
| GitHub sign-in or token | The runtime (the AppHost passes the token) |
| Connection token | The AppHost generates it, the runtime requires it, services send it |
| BYOK provider endpoint and key | The AppHost puts them in the model connection string; the service passes them to its session |
| MCP server URLs | The AppHost; the runtime calls the servers |
| Sessions, tools, permissions, conversations | Each service, through its own SDK or integration |
| Telemetry | The runtime exports its own; each service exports its own |

Services never need a GitHub token. An external runtime manages its own authentication, and the
Copilot SDKs reject a GitHub token on a client that connects to one.

## One runtime per trust boundary

A runtime holds one GitHub identity and stores the sessions of every client that connects to it.
Every service that holds the connection token can use that identity and can resume sessions by
id. Share one runtime between services that trust each other. Add a second runtime, with its own
token, for services that must stay apart:

```cs
var internalCopilot = builder.AddCopilot("copilot-internal");
var publicCopilot = builder.AddCopilot("copilot-public")
                           .WithGitHubToken(builder.AddParameter("public-github-token", secret: true));
```

## Why the AppHost is C#

`CopilotSdk.Aspire.Hosting` is a .NET library that extends the Aspire application model, so the
AppHost that uses it is a C# project. That is the only .NET requirement. Aspire runs services in
other languages through its own hosting integrations (`Aspire.Hosting.Java`,
`Aspire.Hosting.JavaScript`, `Aspire.Hosting.Python`), or as any executable, container, or
Dockerfile.
