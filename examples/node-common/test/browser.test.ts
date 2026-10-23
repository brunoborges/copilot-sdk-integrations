import assert from "node:assert/strict";
import { test } from "node:test";
import { createServer } from "node:http";
import { chromium } from "playwright";
import { captureSnapshot } from "../src/snapshot.js";
import { renderHtml } from "../src/html.js";
import { accessibilityOutput } from "../src/testing.js";

test("real browser capture blocks scripts, subresources and redirects; standalone HTML filter works", {
  skip: process.env.RUN_BROWSER_TESTS !== "1",
}, async () => {
  const requests: string[] = [];
  const server = createServer((request, response) => {
    requests.push(request.url ?? "");
    if (request.url === "/redirect") {
      response.writeHead(302, { location: "/unexpected" }).end();
      return;
    }
    response.setHeader("content-type", "text/html");
    response.end('<h1>Static exhibit</h1><button></button><input><a href="/other">click here</a><img src="/image"><script>document.body.textContent="SCRIPT EXECUTED"</script>');
  });
  await new Promise<void>((done) => server.listen(0, "127.0.0.1", done));
  try {
    const address = server.address();
    assert(address && typeof address !== "string");
    const origin = `http://127.0.0.1:${address.port}`;
    const snapshot = await captureSnapshot(`${origin}/`);
    assert.match(snapshot, /Static exhibit/);
    assert(!snapshot.includes("SCRIPT EXECUTED"));
    assert.deepEqual(requests, ["/"]);
    await assert.rejects(captureSnapshot(`${origin}/redirect`));
    assert(!requests.includes("/unexpected"));
    const browser = await chromium.launch({ channel: "chromium" });
    try {
      const page = await browser.newPage();
      await page.setContent(renderHtml({ ...accessibilityOutput, review: 'Review <script>window.injected=true</script>' }, origin));
      assert.equal(await page.locator(".finding:visible").count(), 3);
      await page.locator("#filter").fill("4.1.2");
      assert.equal(await page.locator(".finding:visible").count(), 1);
      assert.equal(await page.locator("#count").textContent(), "1 of 3 findings shown");
      assert.equal(await page.evaluate(() => (globalThis as unknown as { injected?: boolean }).injected), undefined);
    } finally {
      await browser.close();
    }
  } finally {
    await new Promise<void>((done, reject) => server.close((error) => error ? reject(error) : done()));
  }
});
