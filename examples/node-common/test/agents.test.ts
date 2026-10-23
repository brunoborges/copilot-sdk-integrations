import assert from "node:assert/strict";
import { test, mock } from "node:test";
import { constants } from "node:fs";
import { open, unlink, symlink, writeFile, mkdir, rm } from "node:fs/promises";
import { resolve } from "node:path";
import { evaluatePermission } from "@copilot-sdk-integrations/agent-core";
import { approvedFactsSchema, createMuseum, exhibits, validateExhibit } from "../src/museum.js";
import { createAccessibility, validateAccessibility, type Criterion } from "../src/accessibility.js";
import { MAX_SNAPSHOT_BYTES, SnapshotStore, exactTarget } from "../src/snapshot.js";
import { museumOutput, accessibilityOutput, snapshotText, testTarget } from "../src/testing.js";
import { createResearch, wikipediaPermissions } from "../src/research.js";
import { renderHtml } from "../src/html.js";
import { prepareCommand } from "../src/cli.js";
import { loadWorkshopConfig } from "../src/config.js";
import { loadInstructions } from "../src/resources.js";

const facts = exhibits.apollo11.facts;
const retrieved = new Set([1, 2, 3, 4, 5]);
const checked = new Map<Criterion, number>([["4.1.2", 1], ["3.3.2", 1], ["2.4.4", 1]]);

test("museum accepts bounded approved facts and rejects unapproved or malformed output", () => {
  assert(approvedFactsSchema.safeParse(facts).success);
  for (const input of [[], Array(21).fill("fact"), [""], ["x".repeat(501)]]) {
    assert(!approvedFactsSchema.safeParse(input).success);
  }
  assert.equal(validateExhibit(JSON.stringify(museumOutput), facts, retrieved).questions.length, 3);
  const invalid = [
    { ...museumOutput, narrative: "Too short." },
    { ...museumOutput, narrative: Array(141).fill("word").join(" ") },
    { ...museumOutput, questions: ["Same?", "same ?", "Other?"] },
    { ...museumOutput, questions: ["First?", "Second?", "Missing mark"] },
    { ...museumOutput, questions: ["One? Two?", "Three?", "Four?"] },
    { ...museumOutput, title: "Title?" },
    { ...museumOutput, factIds: [99] },
    { ...museumOutput, factIds: [1, 1] },
    { ...museumOutput, additional: true },
  ];
  for (const value of invalid) assert.throws(() => validateExhibit(JSON.stringify(value), facts, retrieved));
  assert.throws(() => validateExhibit(JSON.stringify(museumOutput), facts, new Set()));
  assert.throws(() => validateExhibit("```json\n{}\n```", facts, retrieved));
});

test("accessibility requires 3–5 findings, exact evidence and a lookup for each finding", () => {
  assert.equal(validateAccessibility(JSON.stringify(accessibilityOutput), snapshotText, checked).findings.length, 3);
  const invalid = [
    { ...accessibilityOutput, findings: accessibilityOutput.findings.slice(0, 2) },
    { ...accessibilityOutput, findings: Array(6).fill(accessibilityOutput.findings[0]) },
    { ...accessibilityOutput, findings: [accessibilityOutput.findings[0], accessibilityOutput.findings[0], accessibilityOutput.findings[2]] },
    { ...accessibilityOutput, findings: accessibilityOutput.findings.map((finding) => ({ ...finding, evidence: "invented evidence" })) },
    { ...accessibilityOutput, findings: accessibilityOutput.findings.map((finding) => ({ ...finding, criterion: "9.9.9" })) },
    { ...accessibilityOutput, reviewLimits: "" },
  ];
  for (const value of invalid) assert.throws(() => validateAccessibility(JSON.stringify(value), snapshotText, checked));
  assert.throws(() => validateAccessibility(JSON.stringify(accessibilityOutput), snapshotText, new Map()), /EACH/);
});

test("policies approve only explicit app tools, never shell, file, URL, MCP or managed requests", async () => {
  const store = await SnapshotStore.create();
  try {
    for (const definition of [
      createMuseum("Apollo", facts).definition,
      createAccessibility(testTarget, store).definition,
    ]) {
      assert.equal(definition.builtInTools, false);
      const names = new Set((definition.tools ?? []).map((tool) => (tool as { name: string }).name));
      const context = { agent: definition, customToolNames: names, context: { attributes: {} } };
      for (const name of names) {
        assert.equal(await evaluatePermission(definition.permissions, { kind: "custom-tool", toolName: name } as never, context), "approve");
        assert.equal(await evaluatePermission(definition.permissions, { kind: "custom-tool", toolName: name, managedApprovalRequired: true } as never, context), "deny");
      }
      for (const kind of ["shell", "write", "read", "url", "mcp", "custom-tool"]) {
        assert.equal(await evaluatePermission(definition.permissions, { kind, toolName: "not_allowed" } as never, context), "deny");
      }
    }
  } finally {
    await store.dispose();
  }
});

test("target is canonical, exact and HTTP(S) only", () => {
  assert.equal(exactTarget(testTarget), testTarget);
  for (const target of ["file:///etc/passwd", "https://user:pass@example.com/", "https://example.com/#fragment", "https://example.com"]) {
    assert.throws(() => exactTarget(target));
  }
});

test("snapshot is isolated, fresh, bounded, immutable and permits one navigation", async () => {
  const store = await SnapshotStore.create();
  const other = await SnapshotStore.create();
  try {
    assert.notEqual(store.directory, other.directory);
    await assert.rejects(store.read(), /fresh/);
    await store.capture(testTarget, async () => snapshotText);
    assert.equal(await store.read(), snapshotText);
    await assert.rejects(other.read(), /fresh/);
    await assert.rejects(store.capture(testTarget, async () => snapshotText), /one navigation/);
    const realNow = Date.now;
    mock.method(Date, "now", () => realNow() + 121_000);
    await assert.rejects(store.read(), /fresh/);
    mock.restoreAll();
    await writeFile(store.path, "changed");
    await assert.rejects(store.read(), /changed/);
  } finally {
    mock.restoreAll();
    await store.dispose();
    await other.dispose();
  }
});

test("snapshot reader refuses file symlinks, oversized files and directory symlinks", async () => {
  const store = await SnapshotStore.create();
  const other = await SnapshotStore.create();
  try {
    await store.capture(testTarget, async () => snapshotText);
    await other.capture(testTarget, async () => snapshotText);
    await unlink(store.path);
    await symlink(other.path, store.path);
    await assert.rejects(store.read());
    await unlink(store.path);
    const file = await open(store.path, constants.O_WRONLY | constants.O_CREAT, 0o600);
    await file.truncate(MAX_SNAPSHOT_BYTES + 1);
    await file.close();
    await assert.rejects(store.read(), /Unsafe/);
    await rm(store.directory, { recursive: true });
    await symlink(other.directory, store.directory);
    await assert.rejects(store.read(), /Unsafe/);
  } finally {
    await store.dispose();
    await other.dispose();
  }
});

test("capture rejects empty/oversized snapshots and pre-existing files", async () => {
  for (const text of ["", "x".repeat(MAX_SNAPSHOT_BYTES + 1)]) {
    const store = await SnapshotStore.create();
    try {
      await assert.rejects(store.capture(testTarget, async () => text), /Empty or oversized/);
      await assert.rejects(store.read(), /fresh/);
    } finally {
      await store.dispose();
    }
  }
  const store = await SnapshotStore.create();
  try {
    await writeFile(store.path, "stale");
    await assert.rejects(store.capture(testTarget, async () => snapshotText));
    await assert.rejects(store.read(), /fresh/);
  } finally {
    await store.dispose();
  }
});

test("run parent must not be a symlink", async () => {
  const directory = resolve(".runs");
  await mkdir(directory, { recursive: true });
  const link = resolve(".runs", "parent-link");
  try {
    await symlink(directory, link);
    await assert.rejects(SnapshotStore.create(link), /Unsafe/);
  } finally {
    await unlink(link);
  }
});

test("research permits only the exact Wikipedia server and search/read names, and validates observed citations", async () => {
  const research = createResearch("Apollo 11");
  const context = { agent: research.definition, customToolNames: new Set<string>(), context: { attributes: {} } };
  for (const toolName of ["search", "readArticle", "wikipedia-search", "wikipedia-readArticle"]) {
    assert.equal(await evaluatePermission(wikipediaPermissions, { kind: "mcp", serverName: "wikipedia", toolName } as never, context), "approve");
    assert.equal(await evaluatePermission(wikipediaPermissions, { kind: "mcp", serverName: "other", toolName } as never, context), "deny");
    assert.equal(await evaluatePermission(wikipediaPermissions, { kind: "custom-tool", serverName: "wikipedia", toolName } as never, context), "deny");
    assert.equal(await evaluatePermission(wikipediaPermissions, { kind: "mcp", serverName: "wikipedia", toolName, managedApprovalRequired: true } as never, context), "deny");
  }
  assert.equal(await evaluatePermission(wikipediaPermissions, { kind: "mcp", serverName: "wikipedia", toolName: "write" } as never, context), "deny");
  const notes = { notes: [{ claim: "Apollo 11 landed humans on the Moon.", sourceUrl: "https://en.wikipedia.org/wiki/Apollo_11" }] };
  assert.throws(() => research.validate(JSON.stringify(notes)));
  await research.definition.hooks!.onPostToolUse!({
    toolName: "wikipedia-readArticle", toolArgs: {}, timestamp: new Date(), sessionId: "offline", workingDirectory: process.cwd(),
    toolResult: { resultType: "success", textResultForLlm: "Read https://en.wikipedia.org/wiki/Apollo_11" },
  }, { sessionId: "offline" });
  assert.match(research.validate(JSON.stringify(notes)).review, /Unapproved/);
  assert.throws(() => research.validate(JSON.stringify({
    notes: [{ claim: "invented", sourceUrl: "https://en.wikipedia.org/wiki/Unobserved" }],
  })));
});

test("HTML renderer escapes untrusted fields and includes static filtering with live count", () => {
  const review = validateAccessibility(JSON.stringify(accessibilityOutput), snapshotText, checked);
  const report = renderHtml({ ...review, review: '<script>alert("x")</script>' }, 'https://example.com/?x="><img src=x>');
  assert(!report.includes('<script>alert("x")</script>'));
  assert(!report.includes("<img src=x>"));
  assert(report.includes("&lt;script&gt;"));
  assert(report.includes('id="filter"'));
  assert(report.includes('aria-live="polite"'));
  assert(report.includes("default-src 'none'"));
  const exhibit = renderHtml(validateExhibit(JSON.stringify(museumOutput), facts, retrieved));
  assert(exhibit.includes("<ol>"));
  assert(exhibit.includes("Approved fact IDs: 1, 2, 3, 4, 5"));
});

test("CLI accepts all bundled topics, research and exact target with optional application-side HTML", async () => {
  for (const name of ["apollo11", "reef", "terracotta"]) {
    const command = await prepareCommand(["museum", name, "--html", "exhibit.html"]);
    assert.equal(command.definition.name, "museum-exhibit");
    assert.equal(command.outputHtml, "exhibit.html");
    await command.dispose();
  }
  const research = await prepareCommand(["research", "Apollo 11"]);
  assert.equal(research.definition.name, "museum-research");
  const review = await prepareCommand(["accessibility", testTarget]);
  assert.equal(review.definition.name, "web-accessibility");
  await review.dispose();
  await assert.rejects(prepareCommand(["museum", "unknown"]));
  await assert.rejects(prepareCommand(["museum", "reef", "--html"]));
});

test("packaged prompt resources resolve relative to ESM modules, not the working directory", () => {
  for (const name of ["curator", "reviewer", "researcher"] as const) {
    const text = loadInstructions(name);
    assert(text.length > 300);
    assert(text.includes("ONLY"));
  }
  const instructions = createMuseum("Apollo", facts).definition.instructions as { content: string };
  assert.equal(instructions.content, loadInstructions("curator"));
});

test("external configuration uses bounded validated operational settings, not safety overrides", () => {
  assert.deepEqual(loadWorkshopConfig({}), {
    model: undefined, responseTimeout: 120_000, mcpTimeout: 30_000,
    browser: { headless: true, channel: "chromium", launchTimeout: 30_000, navigationTimeout: 30_000, snapshotTimeout: 10_000 },
  });
  const config = loadWorkshopConfig({
    COPILOT_MODEL: "test-model", COPILOT_RESPONSE_TIMEOUT_MS: "45000", COPILOT_MCP_TIMEOUT_MS: "12000",
    PLAYWRIGHT_HEADLESS: "false", PLAYWRIGHT_CHANNEL: "chrome", PLAYWRIGHT_LAUNCH_TIMEOUT_MS: "15000",
    PLAYWRIGHT_NAVIGATION_TIMEOUT_MS: "20000", PLAYWRIGHT_SNAPSHOT_TIMEOUT_MS: "5000",
    BUILT_IN_TOOLS: "true", ALLOW_REDIRECTS: "true",
  });
  assert.equal(config.model, "test-model");
  assert.equal(config.responseTimeout, 45_000);
  assert.equal(config.mcpTimeout, 12_000);
  assert.deepEqual(config.browser, { headless: false, channel: "chrome", launchTimeout: 15_000, navigationTimeout: 20_000, snapshotTimeout: 5000 });
  assert(!("BUILT_IN_TOOLS" in config));
  for (const [key, value] of [
    ["COPILOT_MODEL", ""], ["COPILOT_RESPONSE_TIMEOUT_MS", "0"], ["COPILOT_RESPONSE_TIMEOUT_MS", "600001"],
    ["COPILOT_MCP_TIMEOUT_MS", "NaN"], ["PLAYWRIGHT_HEADLESS", "yes"],
    ["PLAYWRIGHT_CHANNEL", "arbitrary"], ["PLAYWRIGHT_NAVIGATION_TIMEOUT_MS", "120001"],
    ["PLAYWRIGHT_SNAPSHOT_TIMEOUT_MS", "999"], ["PLAYWRIGHT_LAUNCH_TIMEOUT_MS", "1.5"],
  ]) {
    assert.throws(() => loadWorkshopConfig({ [key]: value }), new RegExp(key!));
  }
});
