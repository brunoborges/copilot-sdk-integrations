# Shared Java workshop logic

This module backs the runnable [Spring Boot](../spring-boot), [Quarkus](../quarkus),
and [Micronaut](../micronaut) CLIs. It is not a fourth framework integration.
Each framework discovers its own declarative `@CopilotAgent` interfaces and injects
their proxies and named tools. The application never constructs an SDK client or session.

Based on the [Copilot SDK workshop](https://github.github.com/copilot-sdk-workshop/).
The exercises are implemented, not copied verbatim.

## Prompts and external configuration

Durable prompts live in packaged UTF-8 classpath resources:
`workshop/prompts/museum.txt`, `accessibility.txt`, and `research.txt` under this
module's `src/main/resources/`. Every framework agent uses
`@CopilotAgent(instructionsResource = "classpath:/workshop/prompts/…")`, so the
integration's resource loader reads the shared dependency jar; no filesystem
working-directory assumption or application-side prompt loading is needed.
Edit these files to revise policy wording, then rebuild java-common and the
framework application. Framework tests compare the loaded instructions against
all three resource files. Built Spring Boot/Quarkus distributions retain this jar
and its resources; Micronaut's exec classpath uses the same packaged jar.

Operational settings are in each application's `src/main/resources/application.properties`:

| Setting | Spring Boot | Quarkus | Micronaut |
| --- | --- | --- | --- |
| Per-agent model (optional) | `copilot.agents.<name>.model` | `quarkus.copilot.agents.<name>.model` | `micronaut.copilot.agents.<name>.model` |
| Per-agent response timeout | `copilot.agents.<name>.response-timeout` | `quarkus.copilot.agents.<name>.response-timeout` | `micronaut.copilot.agents.<name>.response-timeout` |
| Runtime startup timeout | `copilot.startup-timeout` | `quarkus.copilot.startup-timeout` | `micronaut.copilot.startup-timeout` |

Names are `museum`, `accessibility`, and `research`. No model is hardcoded:
omitting it preserves the Copilot runtime's default. Per-agent response timeouts
default to `180s` and startup timeouts to `30s` in these example property files.
The framework/SDK applies response timeouts; the CLI does not impose a second,
hardcoded outer timeout. These are per-runtime-operation timeouts, not a total
wall-clock deadline including connection and cleanup.

All three applications also bind `workshop.browser.headless=true` and
`workshop.browser.timeout-ms=30000` through native DI configuration annotations.
The positive browser timeout applies to launch, navigation, response interception,
and snapshot extraction. Use normal framework property/system/environment
overrides; framework tests exercise overriding browser settings and model choice.
Examples of command-line system-property overrides appear in each framework README.

URL scope, GET-only capture, redirect blocking, file bounds, approved-facts checks,
tool allowlists, permission handlers, and the pinned Wikipedia MCP package/tool
selection remain strict in code. Do not override integration tool/provider
settings to widen these examples' capabilities.

## Prerequisites and offline tests

JDK 21+ for the shared module, Spring Boot and Quarkus; JDK 25+ for Micronaut.
The first three already compile with `--release 21`. Micronaut's framework
5.2.17 dependencies and the existing integration/processor contain Java 25
class files (major 69), so lowering only the example's compiler cannot support 21.
JDK 25 is a convenient common CI baseline. Use the repository's Maven wrappers.
Building initially downloads Maven dependencies; tests then run without Copilot
credentials, model calls, Wikipedia, Node, or a browser:

```bash
# From repository root; installs core transitively.
./java/spring-boot/mvnw -q -f java/spring-boot/pom.xml \
  -pl copilot-spring-boot-starter,copilot-spring-boot-test -am install -DskipTests
./java/spring-boot/mvnw -q -f examples/java-common/pom.xml install
# Once dependencies are cached, the same deterministic suite also works with -o.
./java/spring-boot/mvnw -o -q -f examples/java-common/pom.xml test
```

To use live agents, install the GitHub Copilot CLI, authenticate using its `/login`
flow (or supply `COPILOT_GITHUB_TOKEN`), and have access to a supported model. The default model and ambient authentication
are supplied by the framework integration. No keys are stored in these examples.
Optional research additionally needs Node.js 22+ and npm/npx.

Accessibility uses Playwright Java **1.58.0**. Install its matching Chromium once:

```bash
./java/spring-boot/mvnw -q -f examples/java-common/pom.xml exec:java \
  -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"
# On Linux CI, install required OS libraries too:
# ... -Dexec.args="install --with-deps chromium"

# Optional real-browser test: local fixture only, no Copilot or public website.
./java/spring-boot/mvnw -q -f examples/java-common/pom.xml test \
  -Dworkshop.browserTest=true
```

The ordinary suite skips the browser fixture. It checks strict JSON, output
constraints, fact bounds, tool scope/permissions, evidence and rule lookups,
snapshot freshness/size/symlinks, HTML escaping, and overwrite prevention.
Framework tests separately exercise injected agent proxies with their own
integration's fake runtime, including generated `@CopilotTool` metadata.

## Museum workflow

`museum apollo11`, `museum reef`, and `museum terracotta` select four bundled
facts each. These are illustrative educator-approved *inputs*, not a claim that
an actual educator has endorsed this repository. An educator must review them
before real museum use. No Wikipedia tools are available to the curator.

To bring your own facts, create `approved.json` in the CLI's working directory:

```json
{
  "title": "Your exhibit",
  "educatorApproved": true,
  "facts": [
    "A statement an educator has checked.",
    "Another checked statement."
  ]
}
```

Use `museum approved.json`; a sample is in `approved-facts.sample.json`.
Only a local `.json` basename is accepted, never a symlink; input is limited
to 64 KiB. There must be 1–20 nonempty facts, each at most 500 Unicode
characters. `educatorApproved:true` is an explicit workflow acknowledgment,
**not authentication or proof of actual approval**.

The read-only `approved_fact_lookup` tool returns only the selected exhibit.
The durable curator policy prohibits outside facts. Task data is JSON, not
interpolated system instructions. Deterministic validation requires:

* One strict JSON object containing only `title`, `narrative`, and `questions`.
* A nonempty title and a 100–140-whitespace-separated-word narrative.
* Exactly three textually distinct questions, each ending in one `?`; no
  question marks in title or narrative.
* An actual approved-facts tool invocation in this run.

**These structural checks do not prove factual grounding**, originality, quality,
or that all assertions are supported. Review the narrative and questions manually.
Invalid output is rejected, not silently repaired or printed as a valid exhibit.

## Separate, optional Wikipedia research

`research "Apollo 11"` runs a **different declarative agent**, with the
`wikipedia` MCP server launched as:

```text
npx -y wikipedia-mcp@1.0.3
```

Only `search` and `readArticle` are exposed. The permission handler requires both
the exact `wikipedia` server and those tool names (including the runtime aliases
`wikipedia-search` and `wikipedia-readArticle`). Other MCP servers, custom tools,
built-in tools, shell/file/network permissions, and managed approvals are denied.
No MCP process is needed for museum/accessibility sessions.

Research returns `{ "title": "...", "facts": [{"text":"...", "source":"https://en.wikipedia.org/wiki/..."}] }`.
The policy requires searching and reading articles, not relying on snippets.
Code checks 1–20 bounded candidates and Wikipedia citation URL shape; it does
**not** verify that tools were used, that citations exist, or that sources support
the statements. This is explicitly unapproved research, never automatically fed
to the curator. An educator must open citations, check statements, then manually
write an approved facts file with string facts and `educatorApproved:true`.

## Accessibility workflow and deliberate workshop deviation

`accessibility https://example.com/` performs one trusted application-side
Playwright capture **before** invoking the reviewer. Unlike the workshop's
`@playwright/mcp@0.0.78` composition, this implementation does **not** launch
Playwright MCP or give the model `browser_navigate`.

This narrows the model's capabilities: the model can only call
`read_latest_accessibility_snapshot` and `accessibility_rule_lookup`. The trusted
capture accepts one exact absolute ASCII HTTP(S) URL without credentials or
fragments. Navigation requests must match it byte-for-byte; HTTP redirects are
aborted before following them. Non-GET requests are blocked. HTTP(S) GET
subresources (including third-party resources) may load; this is **not a network
sandbox** and GET requests themselves can have side effects. No clicks or
interactive login are performed. Use only public pages or pages you control,
from an isolated OS account/container, not sensitive authenticated sites.
Canonicalization redirects, missing trailing slashes, and blocked embedded frame
navigations can make a capture fail; request the final exact URL instead.

Each capture writes one bounded UTF-8 accessibility-tree file in a new,
owner-only `.workshop-run-<UUID>/` directory under the current working directory.
The tool accepts **no path argument**. Reads reject absent, stale, replaced,
symlink, or >256-KiB files. Normal completion/failure deletes it. A crash may
leave the directory; a future run never reuses it. Copilot/runtime session
storage can retain supplied data separately: do not supply secrets.

The reviewer must read the snapshot and call the local WCAG catalog once
**per finding**, including repeated criteria. Results must contain exactly
`findings` and `reviewLimits`, with 3–5 findings each containing `evidence`,
`criterion`, and `remediation`. Evidence must be a literal substring of this
snapshot, criteria must have been looked up, and duplicate evidence/criterion
pairs are rejected. Fewer than three supported issues means **rejection**, not
permission to fabricate findings. A review failing that contract produces no report.

The catalog is a small paraphrased subset of [WCAG 2.2](https://www.w3.org/TR/WCAG22/):
1.1.1, 1.3.1, 2.4.4, 2.4.6, 3.3.2, 4.1.2. Evidence presence and criterion lookup
do not prove that a violation exists. Keyboard, focus, contrast, dynamic behavior,
visual context, and assistive-technology behavior need human testing.
**This is not a WCAG audit or conformance certification.**

## HTML capstones and operational boundaries

Append a new filename, e.g. `museum reef exhibit.html` or
`accessibility https://example.com/ report.html`. After validation, trusted code
renders a standalone HTML document with escaped data. The museum page includes
narrative, questions and educator-review limits. The accessibility page includes
the exact target, evidence, criteria, remediation, limitations, a labeled text
filter and a polite live finding count. It works offline with no CDN dependencies.
HTML filenames must be basenames ending in `.html`; existing files and symlinks
are never overwritten. Research has no HTML mode.

This deliberately replaces the workshop's model-authored filesystem capstone:
the agent never gets file-write permission or a shell. Rendering is deterministic,
not an HTML-design agent. All agents have explicit source-qualified tool
allowlists, so built-in shell, file, browser, and subagent tools are unavailable.
Application tool permission policies approve only the registered read-only tools.

Each executable is a **single-user, single-command CLI**, not an HTTP service.
The singleton tool state is intentional for that lifetime and must not be reused
as a concurrent server without request-scoped state and additional isolation.
Each invocation has a fresh conversation ID; the managed framework context closes
the SDK client on exit. Agent response timeouts are externally configured (180s
by default); failures terminate with an error rather than certifying or publishing
invalid output.
