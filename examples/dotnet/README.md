# .NET workshop agents

The Museum Exhibit and Web Accessibility agents use this repository's
`CopilotSdk.Extensions.Hosting` integration: `AddCopilot()`, DI-registered tools,
`Microsoft.Extensions.AI.IChatClient`, typed replies and Generic Host lifecycle.
No raw SDK client or session is created by the application.

## Run

Install the **.NET 10 SDK** and authenticate the Copilot CLI. Run these commands
from the **repository root**; the project reference builds the unreleased integration
from source:

```sh
dotnet build examples/dotnet/Workshop.csproj
dotnet run --project examples/dotnet -- --help
dotnet run --project examples/dotnet -- museum --facts apollo11
dotnet run --project examples/dotnet -- museum --facts reef --research --html exhibit.html
dotnet run --project examples/dotnet -- accessibility --url https://example.com --html accessibility-report.html
```

Use `Copilot__Model` to select a model. The integration binds its native
`Copilot__*` settings, including `Copilot__GitHubToken`, or uses the SDK's ambient
authentication. Never commit credentials. Live runs consume Copilot account usage.
The SDK downloads its runtime on the first build.

## Configuration and prompt resources

`appsettings.json` is copied to the build output and loaded through .NET
`IConfiguration`, followed by optional `appsettings.Local.json` and environment
variables. Named agents bind to `Copilot:Agents:museum`, `accessibility` and
`research`; the file sets their response timeouts, while the runtime/model/identity
settings use the integration's ordinary `Copilot` section.

```sh
Copilot__Agents__museum__Model=gpt-5 \
Copilot__Agents__museum__ResponseTimeout=00:01:00 \
  dotnet run --project examples/dotnet -- museum
Workshop__Browser=chromium Workshop__Headless=false \
  dotnet run --project examples/dotnet -- accessibility --url https://example.com
```

Long instructions live in `Prompts/curator.txt`, `reviewer.txt` and `researcher.txt`.
They are .NET embedded resources loaded from the application assembly, so published
applications do not depend on the current working directory. Missing or empty prompts
fail explicitly. Tool allowlists, source validation and exact-target authorization
remain code-enforced rather than editable prompt guidance.

Browser review and optional research need Node.js 20.19+ with `npx`. The browser
MCP server is pinned to `@playwright/mcp@0.0.78`; install its matching Chromium:

```sh
PLAYWRIGHT_SKIP_BROWSER_GC=1 npx --yes @playwright/mcp@0.0.78 install-browser chromium
```

## Museum

`--facts apollo11|reef|terracotta` selects a bundled educator-approved fact set.
`--facts-file facts.json` accepts `{"facts":["..."]}`, bounded to 20 KB, 1-20 facts
and 500 characters per fact. The CLI prints these facts before generating an exhibit.

The curator must call `approved_fact_lookup`. With `--research`, a separate session
uses only `wikipedia-mcp@1.0.3` search/read tools and validates cited Wikipedia URLs.
Its accepted summary is frozen into `approved_wikipedia_fact_lookup`, not merged
into educator-approved facts. Research failure fails explicitly.

Typed output is validated in application code: one single-line title, 100-140
whitespace-delimited narrative words, exactly three distinct questions ending in
`?`, and the required lookup calls. Structural validation does not establish factual
grounding; generated claims require human review.

## Accessibility

`--url` is an explicit absolute HTTP(S) target. The browser agent receives only
Playwright's `browser_navigate`, restricted to that exact target, plus two local
read-only tools: `read_latest_accessibility_snapshot` and
`accessibility_rule_lookup`. Shell, other browser tools and general file operations
are not allowlisted. A new temporary directory isolates every run; the reader
rejects missing, empty, symlinked and oversized (over 1 MB) snapshots.

The structured report requires 3-5 findings with observed evidence, a local WCAG
catalog criterion and actionable remediation. The application checks that the
snapshot and relevant rules were read before publishing. Evidence remains subject
to human review. The output is explicitly a focused review, **not a full WCAG
conformance audit**.

Only review trusted pages you are authorized to visit. Exact navigation is not an
egress sandbox: pages can redirect/load subresources, and MCP servers have the
application's OS privileges. This local CLI is not a public URL-fetching service.

## HTML and workshop adaptations

Both modes accept `--html path.html`. Application code escapes the validated data,
renders one standalone semantic HTML document, and adds a keyboard-accessible
filter with a live visible count. Existing files are never overwritten.
Unlike the workshop's model-written capstone, the model receives no write permission.
Structured results are buffered and validated before printing, not streamed as
unvalidated tokens.

## Offline verification

```sh
dotnet run --project examples/dotnet/tests/Workshop.Tests.csproj
```

Tests cover word-count boundaries, invalid structures, bounded facts, research URL
validation, exact-target permissions, snapshot isolation, HTML escaping and both
application flows through a started Generic Host. A scripted `IChatClient` replaces
model responses for the application-flow tests. The integration's own protocol,
tool-dispatch and runtime tests live under `dotnet/tests/`; this example does not
access internal test seams. No tests launch Copilot, browsers or MCP servers, require
credentials, or make paid requests.
