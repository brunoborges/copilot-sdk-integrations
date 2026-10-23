# Workshop agent examples

Build the **Museum Exhibit Agent** and **Web Accessibility Agent** from the
[Copilot SDK workshop](https://github.github.com/copilot-sdk-workshop/) using
this repository's framework integrations rather than unmanaged SDK clients.

| Framework | Example | Application runtime |
| --- | --- | --- |
| Spring Boot | [spring-boot](spring-boot/README.md) | Java 21+ |
| Quarkus | [quarkus](quarkus/README.md) | Java 21+ |
| Micronaut | [micronaut](micronaut/README.md) | Java 25+ (Micronaut 5) |
| NestJS | [nestjs](nestjs/README.md) | Node.js 22 LTS, 22.12+ |
| FastAPI | [fastapi](fastapi/README.md) | Python 3.11+ |
| Fastify | [fastify](fastify/README.md) | Node.js 22 LTS, 22.12+ |
| Django | [django](django/README.md) | Python 3.11+ |
| .NET (Generic Host) | [dotnet](dotnet/README.md) | .NET 10 SDK |

Each framework implements **both agents**. Follow its README from a repository
checkout: these integrations are unreleased and must be built or installed locally.
Language-specific common modules share domain contracts and tools, not framework
lifecycle management. There is no separate hosted service to deploy.

Runtime settings use the frameworks' external configuration facilities where
available; long agent instructions are packaged as resource files rather than
inline code. Each README lists its configuration paths and environment overrides.
Tool restrictions and authorization checks remain enforced in application code.

Read the [workshop API and developer-experience comparison](workshop-api-dx-comparison.md)
for differences between the original workshop and these integration-based examples.

## What the examples demonstrate

The museum agent uses bounded educator-approved facts, read-only fact lookup,
curator instructions separated from task data, and deterministic checks for a
single title, a **100-140-word narrative**, and **three distinct visitor questions**.
Supplemental Wikipedia research, where enabled, runs in a separate session and
does not replace educator-approved facts. The documentation for each application
describes its supported options.

The accessibility agent composes a browser accessibility snapshot with a small
local WCAG guidance catalog. Its output contains **3-5 evidence-backed findings**,
criterion references, remediation, and explicit review limits. Tools are restricted
to the review task; instructions alone are not an authorization boundary.

The examples adapt terminal-oriented workshop flows to framework-native applications.
Where HTML export is supported, validated data is escaped and rendered by application
code rather than granting the model general filesystem write permission. See each
README for exact commands, output format and workshop adaptations.

## Runtime and safety

Real agent calls require an authenticated Copilot runtime and consume account usage.
Do not commit credentials. Accessibility and optional research also require Node.js
and the documented browser/MCP setup. Tests use scripted runtimes and local fixtures:
no credentials, live model requests or browser downloads are required for offline tests.

These are **single-user local examples**, not production multi-tenant services.
Only review URLs you trust and are authorized to inspect. Restricting browser tools
or navigation targets is not an egress sandbox: pages can redirect and load
subresources, and MCP servers run with the application's OS privileges. Public
deployments need authentication, authorization, network restrictions, workload
isolation and admission limits.

Structural checks do not prove factual grounding, prevent every prompt injection,
or certify accessibility compliance. Museum claims and accessibility findings
still require human review. An accessibility tree is not a complete keyboard,
visual contrast or assistive-technology audit.

Workshop attribution is in [NOTICE](NOTICE).

## Verification

[Workshop examples CI](../.github/workflows/examples.yml) builds all eight
applications, checks Node.js types, and runs each language's offline contract and
framework tests. It also runs Chromium checks against local fixtures for Java,
Node.js and the pinned browser MCP protocol used by Python/.NET. These checks
exercise navigation restrictions, current-run snapshots and interactive HTML without
sending model requests. Each language guide provides the same local commands.

Live Copilot and Wikipedia research are opt-in and are not exercised by the
credential-free test suites.
