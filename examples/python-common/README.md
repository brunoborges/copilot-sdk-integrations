# Shared Python workshop contracts

The [FastAPI](../fastapi/README.md) and [Django](../django/README.md) applications
implement both finished tracks from the
[Copilot SDK workshop](https://github.github.com/copilot-sdk-workshop/).
This local package shares validated domain models, prompts, bounded tools and CLI
options; each application still uses its own framework integration for registration,
configuration, invocation and lifecycle.

## Prerequisites

Use Python 3.11+ and an installed, authenticated Copilot CLI (`copilot login`).
Set `COPILOT_CLI_PATH` if it is not discoverable and `COPILOT_MODEL` to select a
model. Real runs use your account's quota; never put credentials in source.
The FastAPI settings equivalents are `COPILOT_RUNTIME__CLI_PATH` and
`COPILOT_DEFAULTS__MODEL`. Use those names for the FastAPI example.

## External configuration and resources

FastAPI's large prompts live in this package's `prompts/curator.txt`, `reviewer.txt`
and `researcher.txt`, loaded with Python's `importlib.resources`. Django uses its
native template loader through `instructions_template`, with equivalent files under
`workshop_django/templates/workshop/` and `TEMPLATES["APP_DIRS"]` enabled.
Prompt resources are packaged in installed wheels and do not depend on the working
directory. Missing prompt resources fail explicitly.

FastAPI uses `CopilotSettings` (Pydantic Settings) with packaged `defaults.env`,
then an optional working-directory `.env`, then process environment variables.
That file sets the 120-second request timeout and 90-second research override.
For example:

```sh
COPILOT_AGENTS='{"museum":{"model":"gpt-5","timeout":60}}' \
  examples/.venv/bin/python -m workshop_fastapi museum
WORKSHOP_BROWSER=chromium WORKSHOP_HEADLESS=false \
  examples/.venv/bin/python -m workshop_fastapi accessibility --url https://example.com
```

Django keeps runtime/model and per-agent timeout defaults in its native
`workshop_django/settings.py` `COPILOT` dictionary. Configure it via
`DJANGO_SETTINGS_MODULE` or the documented environment bindings:
`COPILOT_MODEL`, `COPILOT_CLI_PATH`, `COPILOT_MUSEUM_TIMEOUT`,
`COPILOT_ACCESSIBILITY_TIMEOUT`, and `COPILOT_RESEARCH_TIMEOUT`.
The `WORKSHOP` settings dictionary binds `WORKSHOP_BROWSER` and
`WORKSHOP_HEADLESS` (`true`/`false`) for browser launch in either framework.
Permission checks, tool restrictions and output constraints remain application code.

Accessibility and optional Wikipedia research require Node.js 20.19+ and `npx`.
The accessibility agent uses pinned `@playwright/mcp@0.0.78`; install its matching
Chromium before a first run:

```sh
PLAYWRIGHT_SKIP_BROWSER_GC=1 npx --yes @playwright/mcp@0.0.78 install-browser chromium
```

The browser runs headless. Only review pages you trust and are authorized to visit.
Exact-navigation permission is not an outbound-network sandbox: page subresources,
redirects, and MCP server code can make network requests. Do not expose this local
demo as a public URL-fetching service without authentication, egress restrictions,
rate limits and workload isolation.

## Museum

Choose `--facts apollo11`, `--facts reef`, or `--facts terracotta`; all print the
educator-approved facts before generation. Alternatively, `--facts-file facts.json`
accepts an object `{"facts": ["..."]}` containing 1-20 nonblank facts, each at most
500 characters. Files are bounded to 20 KB.

The curator must invoke the read-only `approved_fact_lookup`. `--research` first
runs a separate, read-only `wikipedia-mcp@1.0.3` session restricted to `search` and
`readArticle`. Its cited result is frozen into `approved_wikipedia_fact_lookup`,
never merged into approved facts; those facts take precedence. Research failure
is an explicit error, not silently replaced with an uncited answer.

Structured replies are validated as one single-line title, a 100-140-word narrative
(whitespace-delimited words), and exactly three distinct questions ending in `?`.
Missing required lookup calls fail even if the output shape is correct. Markdown
is printed only after validation, alongside a grounding/human-review disclaimer.
These Python integrations currently expose buffered replies rather than token
streaming, so the examples request typed results and print them after validation.

`--html exhibit.html` writes an escaped, standalone semantic HTML page, with an
accessible question filter, live visible count, embedded CSS/JS and no external
assets. Existing files are never overwritten. Unlike the workshop's model-driven
HTML capstone, rendering here is deterministic application code: the agent never
receives filesystem write permission.

## Accessibility

`accessibility --url https://example.com` exposes only `browser_navigate` from
Playwright MCP plus two local read-only tools. Permission rejects navigation to
anything but the exact scheme, host, port, path, query and fragment supplied by the
caller. Every run gets a fresh temporary snapshot directory, removed on exit.
The snapshot reader rejects symlinks, missing/empty files and files over 1 MB.
Built-in shell, filesystem, browser scripting and other browser MCP tools are not
available to the agent.

The agent reads the current run's accessibility tree, looks up each reported WCAG
criterion in a small local catalog, then returns 3-5 evidence/remediation findings.
The application checks the snapshot read and catalog coverage before printing.
Insufficient evidence or invalid output fails explicitly; this is a focused review,
**not a conformance audit**. A tree alone cannot establish contrast, keyboard
behavior or all other WCAG criteria. Review findings manually.

Add `--html accessibility-report.html` to create an escaped, standalone report with
the target, findings, review limits and an accessible filter/live finding count.
As with the museum page, the application renders validated data and never grants
the agent filesystem-write tools.

## Offline tests

From the repository root, install both integrations and applications together:

```sh
uv pip install --python examples/.venv/bin/python \
  -e python/core -e python/fastapi -e python/django \
  -e examples/python-common -e examples/fastapi -e examples/django \
  pytest pytest-asyncio httpx
examples/.venv/bin/python -m pytest examples/python-common/tests examples/fastapi/tests examples/django/tests
```

Tests use the integrations' scripted runtimes; they do not start Copilot, launch
MCP servers, access the network or consume model quota.

After browser installation, an optional real-MCP smoke test visits only a local
HTTP fixture and verifies that the application's snapshot reader can consume the
pinned MCP server's output:

```sh
WORKSHOP_MCP_TESTS=1 examples/.venv/bin/python -m pytest examples/python-common/tests/test_browser_mcp.py
```

This opt-in starts Chromium and the browser MCP server, but makes no model requests.
