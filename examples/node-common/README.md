# Shared Node workshop implementation

Used by [`../nestjs`](../nestjs/README.md) (`CopilotModule`) and
[`../fastify`](../fastify/README.md) (`copilotPlugin`).

The supported runtime is **Node 22 LTS (`^22.12.0`)**, including browser installation.
The pinned Playwright installer hangs after Chromium download on Node 26.12.1; the official installer
and all shared tests including the real browser smoke test passed with Node 22.23.4.
Set `PLAYWRIGHT_SKIP_BROWSER_GC=1` when installing browsers to preserve unrelated versions in shared caches.

This package contains bounded approved-fact lookup, curator policy, validated exhibit output,
isolated exact-target static Playwright capture, bounded local WCAG lookup, per-finding evidence
validation, separate optional Wikipedia MCP research, and escaped standalone HTML capstones.
It creates no raw Copilot SDK clients: both applications obtain agent handles through their
framework integration.

See either application's README for complete installation, run commands and intentional deviations
from the workshop. The default tests are credential-free. The optional browser smoke test requires
Chromium and uses only a local fixture page.

## Resources and external operational settings

Durable curator, reviewer and researcher instructions live in `src/resources/*.txt`.
`loadInstructions()` resolves them with `new URL(..., import.meta.url)`, independent of the caller's
working directory. The build copies them to `dist/src/resources/`; the package's `files: ["dist"]`
includes these resources. Edit the source resources and rebuild, rather than embedding large prompts
in application code. Tool and permission restrictions remain independently enforced in TypeScript.

`loadWorkshopConfig()` validates only named environment settings: optional `COPILOT_MODEL`,
`COPILOT_RESPONSE_TIMEOUT_MS`, `COPILOT_MCP_TIMEOUT_MS`, `PLAYWRIGHT_HEADLESS`, `PLAYWRIGHT_CHANNEL`,
and the three `PLAYWRIGHT_*_TIMEOUT_MS` launch/navigation/snapshot settings. See each application's
`.env.example` and configuration table. There is intentionally no environment-based tool allowlist,
permission policy, arbitrary browser argument, custom prompt path, or safety toggle.
