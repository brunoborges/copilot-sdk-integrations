# NestJS workshop agents

Runnable single-user CLI examples for the [Copilot SDK workshop](https://github.github.com/copilot-sdk-workshop/).
`NestFactory.createApplicationContext()` boots the real integration's `CopilotModule.forRoot()` and
`forFeature()` functional agents. No HTTP listener, controller, raw SDK replacement, or shared conversation
is involved. The application context owns startup and shutdown.

## Prerequisites and build

Use **Node 22 LTS, version 22.12.0 or later within the 22.x line**, npm, and this repository checkout.
The example manifests declare `^22.12.0`; newer major Node versions are not supported here.
Live agent runs require an installed
[GitHub Copilot CLI](https://docs.github.com/en/copilot/how-tos/copilot-cli) authenticated with `copilot login`
and a Copilot entitlement. Never put credentials in source files. Offline tests require neither credentials
nor a CLI process.

From the repository root:

```sh
npm --prefix nodejs ci
npm --prefix nodejs run build \
  --workspace @copilot-sdk-integrations/agent-core \
  --workspace @copilot-sdk-integrations/ai \
  --workspace @copilot-sdk-integrations/nestjs \
  --workspace @copilot-sdk-integrations/fastify
npm --prefix examples ci
npm --prefix examples run build
npm --prefix examples run typecheck
npm --prefix examples run test
```

`examples/` has its own npm workspace and lockfile. Its `.npmrc` copies **built local integrations**
instead of creating symlinks with broken dependency resolution across the two workspace roots.
Rebuild the integrations **before** installing `examples/`. If integration code changes, reinstall
the copied local packages before testing. Existing `nodejs/` manifests are not modified.

## External configuration and prompt resources

Copy `examples/nestjs/.env.example` to `examples/nestjs/.env` and edit operational settings, or export
the same environment variables. `npm start` uses Node's native `--env-file-if-exists=.env`;
already-exported environment variables take precedence. Direct `node` invocation can use
`--env-file=/path/to/settings.env`. No credentials belong in the example config.

| Setting | Default | Accepted values |
| --- | --- | --- |
| `COPILOT_MODEL` | Runtime-selected model | Nonblank model ID available to your account |
| `COPILOT_RESPONSE_TIMEOUT_MS` | `120000` | Integer `1000`–`600000` |
| `COPILOT_MCP_TIMEOUT_MS` | `30000` | Integer `1000`–`120000` |
| `PLAYWRIGHT_HEADLESS` | `true` | `true` or `false` |
| `PLAYWRIGHT_CHANNEL` | `chromium` | `chromium`, `chrome`, or `msedge`; selected browser must be installed |
| `PLAYWRIGHT_LAUNCH_TIMEOUT_MS` | `30000` | Integer `1000`–`120000` |
| `PLAYWRIGHT_NAVIGATION_TIMEOUT_MS` | `30000` | Integer `1000`–`120000` |
| `PLAYWRIGHT_SNAPSHOT_TIMEOUT_MS` | `10000` | Integer `1000`–`120000` |

Validated environment configuration is supplied through **`CopilotModule.forRootAsync()`** and
the registered agent's model option. This CLI uses native Node env-file loading rather than adding
a `ConfigModule` dependency solely to wrap the same small settings object. Config errors fail before
runtime startup. Builtin/tool permissions, concurrency limits, redirect/subresource blocking, disabled
page scripts/downloads/service workers and output/snapshot limits are **not configurable**.

Large durable instructions are packaged text resources in `../node-common/src/resources/{curator,reviewer,researcher}.txt`.
They are loaded relative to the ESM module with `import.meta.url`, not the working directory. The common
package's build copies them into `dist/src/resources/` and includes them in packaged output. Edit the
source resources and rebuild to change prompt wording; code-enforced safety boundaries remain separate.

## Museum Exhibit Agent

```sh
npm --prefix examples run start --workspace @workshop/nestjs -- museum apollo11
npm --prefix examples run start --workspace @workshop/nestjs -- museum reef --html exhibit.html
npm --prefix examples run start --workspace @workshop/nestjs -- museum terracotta
```

The three bundled, small demo collections are treated as educator-approved inputs for this exercise;
an educator should verify them before real use. To use your own collection, create a JSON array of
**1–20 nonblank facts**, each **at most 500 characters**, then:

```sh
npm --prefix examples run start --workspace @workshop/nestjs -- museum "My exhibit" ./facts.json --html exhibit.html
```

Fact paths and HTML output paths resolve relative to the workspace (`examples/nestjs/`) when using
`npm run --workspace`. Direct `node examples/nestjs/dist/src/main.js ...` uses the caller's working directory.

`approved_fact_lookup` is a read-only, collection-bound tool with 1-based IDs; it cannot retrieve outside facts.
The curator's durable policy prohibits outside factual claims. JSON validation rejects extra keys,
blank titles, narratives outside **100–140 whitespace-delimited words**, anything except exactly **three distinct
questions** ending in a question mark, and fact IDs not actually retrieved. The word limit applies only to
the narrative, not its title or separate question list. A mandatory educator-review disclaimer is added.
These structural and provenance checks **cannot prove semantic factual fidelity**: publication requires human review.

## Separate optional Wikipedia research

```sh
npm --prefix examples run start --workspace @workshop/nestjs -- research "Apollo 11" --html research.html
```

This intentionally separate agent composes the workshop's **`wikipedia-mcp@1.0.3`** via
`npx -y wikipedia-mcp@1.0.3`. The only exposed MCP tools are `search` and `readArticle`; permissions require
the exact `wikipedia` server and one of those tool names (or its `wikipedia-search` /
`wikipedia-readArticle` alias). No arbitrary MCP servers, builtins, shell, file or URL permissions are approved.
It requires network/npm access and a trusted third-party MCP subprocess.

Research emits **unapproved**, source-cited notes. The validator requires a successful article read and exact
Wikipedia article URLs observed in successful article-read results. If a server response has no article URL,
the result fails closed. The citation checks do not establish claim accuracy. A human must check the sources
and explicitly copy selected, edited facts into a separate facts JSON array; research never silently becomes
curator-approved material. Live Wikipedia/Copilot calls are not exercised by offline tests.

## Web Accessibility Agent

Install the pinned Playwright browser once:

```sh
cd examples
PLAYWRIGHT_SKIP_BROWSER_GC=1 npm exec --workspace @workshop/node-common -- playwright install chromium
cd ..
npm --prefix examples run start --workspace @workshop/nestjs -- \
  accessibility https://example.com/ --html report.html
```

Use Node 22 for browser installation as well as builds and runs. The pinned Playwright installer
can hang after completing its Chromium download on **Node 26.12.1**; its official installer and
real-browser smoke tests were verified with **Node 22.23.4**. No manual archive extraction is needed.
`PLAYWRIGHT_SKIP_BROWSER_GC=1` avoids cleanup of other browser versions in a shared browser cache.
If your default shell still uses Node 26, the official installer can explicitly run under Node 22
from the repository root:

```sh
PLAYWRIGHT_SKIP_BROWSER_GC=1 npx --yes --package=node@22 -- \
  node examples/node_modules/playwright/cli.js install chromium
```

Switch the application/test shell to Node 22 too; the installer-only command does not change its default runtime.

Provide an **exact canonical HTTP(S) URL**: include the trailing slash where applicable; credentials and
fragments are refused. Only review pages you own or have permission to inspect.

This implementation uses the workshop's permitted **trusted application-side Playwright alternative**,
not `@playwright/mcp@0.0.78`. The model has a narrowly scoped custom `browser_navigate` tool:
its literal URL schema and handler require the exact CLI target, and only one navigation attempt is allowed.
An isolated browser context has JavaScript, downloads and service workers disabled. Application-side request
routing blocks other document URLs, redirects, frames and **all subresources**. This deliberately sacrifices
CSS/layout/dynamic fidelity to make the navigation boundary straightforward. It is a **static ARIA snapshot
triage**, not the workshop's full interactive-browser experience. Do not expose it as a multi-user URL-fetch API.

`read_latest_accessibility_snapshot` reads only the current run's fixed file in a randomly named,
application-created private directory. No model-selected paths. The reader rejects missing, older-than-two-minute,
modified, non-regular, hard-linked, symlink or over-256-KiB snapshots. The producer uses exclusive creation;
run directories are removed in `finally` blocks. It does not consume arbitrary pre-existing snapshot files.

`accessibility_rule_lookup` uses a bounded local WCAG 2.2 A/AA catalog with official Understanding links.
Each of the **3–5** distinct candidate findings must have a supported criterion, a nonempty
**verbatim snapshot evidence** excerpt, an actionable remediation, and its own catalog lookup.
The report must state review limits; the application adds an unconditional **not a full conformance
assessment** disclaimer. If fewer than three candidates are supportable, the agent must not invent more:
the application fails closed rather than publishing an incomplete report. Snapshot evidence and criterion
associations still need human verification. Contrast, keyboard/focus behavior, dynamic states, visual relationships
and assistive technology are not tested.

## HTML capstones and tests

`--html <new-file.html>` works for both agents (and research). HTML is rendered **deterministically by the
application after output validation**, not by granting the agent filesystem access. All untrusted fields
are HTML-escaped. Files use exclusive creation and are not overwritten. The standalone accessibility report
includes the exact target, evidence, criterion, remediation, review limits, a labeled text filter and a polite
live result count. No CDN assets or model-provided scripts are used.

The offline test suite exercises the real Nest module lifecycle with `createTestCopilot`'s scripted runtime:
tool wiring, disabled builtins, wrong-URL rejection, snapshot isolation/freshness/symlink/size handling,
per-finding lookups, output validation, safe rendering and the optional research configuration.
An additional **opt-in, no-Copilot browser test** uses only a local fixture server:

```sh
RUN_BROWSER_TESTS=1 npm --prefix examples run test --workspace @workshop/node-common
```

The browser test requires Chromium installed as above; default tests skip it. Live model quality, remote
MCP availability, factual correctness and actual website accessibility are not established by scripted tests.
