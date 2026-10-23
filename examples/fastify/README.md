# Fastify workshop agents

Runnable single-user CLI examples for the [Copilot SDK workshop](https://github.github.com/copilot-sdk-workshop/).
The app creates a real Fastify instance, registers this repository's **`copilotPlugin`** with functional
agent definitions, calls `app.ready()`, uses `app.copilot.agent(...)`, and closes the plugin-owned runtime
in `finally`. It intentionally **never listens on a network port**: no public agent endpoint, shared session,
raw SDK replacement or blanket permission approval.

## Prerequisites and build

Use **Node 22 LTS, version 22.12.0 or later within the 22.x line**, npm, and the repository checkout.
The example manifests declare `^22.12.0`; newer Node majors are not supported here. Live runs need the
[GitHub Copilot CLI](https://docs.github.com/en/copilot/how-tos/copilot-cli), authenticated with `copilot login`,
and a Copilot entitlement. Tests need no CLI process or credentials.

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

`examples/` owns its separate workspace/lockfile. Its `.npmrc` copies the **built local integrations**
to avoid cross-workspace symlink resolution problems. Build the integration packages **before**
installing examples; reinstall the copied packages after integration changes. No existing `nodejs/`
manifest changes are needed.

## External configuration and prompt resources

Copy `examples/fastify/.env.example` to `examples/fastify/.env` or export the settings below.
The start script uses Node's native `--env-file-if-exists=.env`; exported environment variables win.
Direct `node` runs can use `--env-file=/path/to/settings.env`. No credentials belong in this example config.

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

The application validates this small configuration object and passes operational settings through
the conventional **`app.register(copilotPlugin, options)`** mechanism and agent registration. No extra
env/config plugin is needed for this CLI. Malformed settings fail before runtime startup. There are no
generic SDK/browser option bags or environment-based permission switches: builtin/tool restrictions,
single-user concurrency limits, exact-target capture, redirect/subresource blocking, disabled scripts/
downloads/service workers and output/snapshot bounds stay fixed in code.

Large durable prompts live in `../node-common/src/resources/{curator,reviewer,researcher}.txt`.
The loader uses an ESM `import.meta.url`-relative URL, independent of the working directory.
The common build copies the resources into `dist/src/resources/` and packages them with compiled code.
Edit those source resources and rebuild to change prompt wording; hard safety controls are independent.

## Museum Exhibit Agent

```sh
npm --prefix examples run start --workspace @workshop/fastify -- museum apollo11
npm --prefix examples run start --workspace @workshop/fastify -- museum reef --html exhibit.html
npm --prefix examples run start --workspace @workshop/fastify -- museum terracotta
npm --prefix examples run start --workspace @workshop/fastify -- museum "My topic" ./facts.json --html exhibit.html
```

For custom topics `facts.json` is an educator-curated JSON array of **1–20 nonblank facts**, each
**at most 500 characters**. The bundled Apollo 11, reef and Terracotta Army examples are small demo
collections treated as approved for the exercise; educators should verify them before real publication.
Paths in npm workspace runs resolve relative to `examples/fastify/`; direct `node .../main.js` runs use
the caller's current directory.

`approved_fact_lookup` is the curator's only factual source: a bounded read-only collection with 1-based
IDs. Durable policy prohibits outside facts. Output must be JSON with a nonblank title, a **100–140-word**
narrative (whitespace-delimited; excludes title/questions), exactly **three distinct questions** each ending
in a single question mark, and retrieved approved fact IDs. Extra keys and invalid citations are rejected.
Structural/provenance validation cannot prove factual accuracy or age suitability; every result carries a
mandatory educator-review disclaimer.

### Optional separate cited research

```sh
npm --prefix examples run start --workspace @workshop/fastify -- research "Apollo 11" --html research.html
```

This separate functional agent uses the workshop's pinned **`npx -y wikipedia-mcp@1.0.3`** server.
Only `search` and `readArticle` are exposed. Permissions require the **exact `wikipedia` server** and
those names or the `wikipedia-search` / `wikipedia-readArticle` aliases. Builtin, shell, file, generic URL
and other-server permissions remain denied.

Network/npm access and trust in the third-party MCP subprocess are required. Research emits unapproved
source-cited notes, not curator-approved facts. Validation requires a successful article read and exact
Wikipedia article URLs observed in successful read results; missing URLs fail closed. A human checks the
claims and sources, then manually creates a separate facts array for museum mode. There is no automatic
research-to-approval path. Offline tests verify configuration/policy/citation handling, not live MCP behavior.

## Web Accessibility Agent

```sh
cd examples
PLAYWRIGHT_SKIP_BROWSER_GC=1 npm exec --workspace @workshop/node-common -- playwright install chromium
cd ..
npm --prefix examples run start --workspace @workshop/fastify -- \
  accessibility https://example.com/ --html report.html
```

Run browser installation under Node 22 as well. The pinned Playwright installer can hang after the
Chromium download reaches 100% on **Node 26.12.1**. The official installer and browser smoke tests
passed under **Node 22.23.4**, without manual archive extraction.
`PLAYWRIGHT_SKIP_BROWSER_GC=1` prevents cleanup of unrelated browser versions in a shared browser cache.
For an explicit Node 22 installer invocation from the repository root:

```sh
PLAYWRIGHT_SKIP_BROWSER_GC=1 npx --yes --package=node@22 -- \
  node examples/node_modules/playwright/cli.js install chromium
```

That command selects Node 22 only for the installer; also use Node 22 for application/test commands.

Use the **exact canonical HTTP(S) URL** including a trailing slash where appropriate. Credentials and
fragments are rejected. Only review explicitly authorized targets.

The implementation deliberately uses the workshop's **trusted application-side Playwright capture
alternative**, not `@playwright/mcp@0.0.78`. A custom `browser_navigate` tool has a literal exact-target
schema and a matching handler check. Only one navigation attempt is permitted. The isolated browser
disables JavaScript, downloads and service workers. Application-side routing blocks redirects, other
document URLs, frames and **all subresources**. This sacrifices CSS/layout/dynamic fidelity and provides
static ARIA snapshot triage, not the full interactive-browser workshop. Never expose this CLI as a public
or multi-user URL-fetch API.

`read_latest_accessibility_snapshot` reads a fixed, bounded file from the current randomly named private
run directory, never a model-specified path or a pre-existing snapshot location. The exclusive writer and
reader reject missing, older-than-two-minute, changed, symlink, hard-linked, non-regular and over-256-KiB
files. Run directories are removed when the command finishes.

`accessibility_rule_lookup` supplies a bounded local WCAG 2.2 A/AA catalog and official Understanding
links. **Each** of **3–5** candidate findings needs its own rule lookup, a supported criterion,
**verbatim snapshot evidence**, and actionable remediation. Duplicate findings, invented evidence, missing
lookups and malformed results are rejected. If fewer than three candidates are supportable, the agent
must not invent issues; no incomplete report is published.

Every report includes review limits and an application-added **not full WCAG conformance** disclaimer.
Evidence-to-criterion mapping remains human judgment. Keyboard/focus behavior, contrast, visual layout,
dynamic UI and assistive technology support are not tested.

## HTML capstones and offline verification

Use `--html <new-file.html>` as the final option for either agent or research. The trusted application
renders the validated JSON, escapes every untrusted field, and exclusively creates the output file
without overwriting existing files or granting model filesystem access. Accessibility HTML includes target,
findings, evidence, criteria, remediation and limits, plus a labeled text filter with a polite live count.
The file is standalone, with no external scripts or CDN assets.

Tests use the integration's scripted runtime and real plugin lifecycle for both agents, wrong-URL
governance and optional research configuration. Shared tests cover fact/output validation, per-finding
lookups, safe HTML, and snapshot freshness, isolation, symlinks, size and tampering.
Optional browser-only smoke coverage uses a **local fixture server**, no credentials or Copilot:

```sh
RUN_BROWSER_TESTS=1 npm --prefix examples run test --workspace @workshop/node-common
```

Install Chromium first; default offline tests skip this opt-in smoke test. Scripted tests do not establish
live model accuracy, remote MCP availability, factual fidelity or website conformance.
