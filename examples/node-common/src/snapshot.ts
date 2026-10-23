import { constants } from "node:fs";
import { lstat, mkdir, open, rm } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { resolve, join } from "node:path";
import { chromium } from "playwright";
import { loadWorkshopConfig } from "./config.js";

export const MAX_SNAPSHOT_BYTES = 256 * 1024;
const MAX_AGE_MS = 120_000;

export function exactTarget(input: string): string {
  const url = new URL(input);
  if (!["https:", "http:"].includes(url.protocol) || url.username || url.password || url.hash) {
    throw new Error("Use an HTTP(S) URL with no credentials or fragment");
  }
  if (url.href !== input) throw new Error(`Use the exact canonical URL: ${url.href}`);
  return input;
}

/** A deliberately restricted capture: no scripts, downloads, redirects, frames or subresources. */
export async function captureSnapshot(target: string): Promise<string> {
  exactTarget(target);
  const config = loadWorkshopConfig().browser;
  const browser = await chromium.launch({ headless: config.headless, channel: config.channel, timeout: config.launchTimeout });
  try {
    const context = await browser.newContext({ javaScriptEnabled: false, acceptDownloads: false, serviceWorkers: "block" });
    const page = await context.newPage();
    let requested = false;
    await context.route("**/*", async (route) => {
      const request = route.request();
      if (!requested && request.url() === target && request.method() === "GET" &&
          request.isNavigationRequest() && request.frame() === page.mainFrame() && !request.redirectedFrom()) {
        requested = true;
        // continue() follows redirects without invoking the route for every hop.
        const response = await route.fetch({ maxRedirects: 0, timeout: config.navigationTimeout });
        if (response.status() >= 300 && response.status() < 400) {
          await route.abort("blockedbyclient");
        } else {
          await route.fulfill({ response });
        }
      } else {
        await route.abort("blockedbyclient");
      }
    });
    const response = await page.goto(target, { waitUntil: "domcontentloaded", timeout: config.navigationTimeout });
    if (!response?.ok() || page.url() !== target) throw new Error("Requested page failed or redirected");
    const snapshot = await page.locator("body").ariaSnapshot({ timeout: config.snapshotTimeout });
    if (page.url() !== target) throw new Error("Page changed during capture");
    return snapshot;
  } finally {
    await browser.close();
  }
}

export type Capture = (target: string) => Promise<string>;

export class SnapshotStore {
  readonly directory: string;
  readonly path: string;
  private captured?: { at: number; ino: number; dev: number; text: string };
  private attempted = false;
  private constructor(directory: string) {
    this.directory = directory;
    this.path = join(directory, "snapshot.txt");
  }

  static async create(parent = resolve(".runs")) {
    await mkdir(parent, { recursive: true, mode: 0o700 });
    if (!(await lstat(parent)).isDirectory() || (await lstat(parent)).isSymbolicLink()) throw new Error("Unsafe run parent");
    const directory = join(parent, randomUUID());
    await mkdir(directory, { mode: 0o700 });
    return new SnapshotStore(directory);
  }

  async capture(target: string, capture: Capture = captureSnapshot) {
    if (this.attempted) throw new Error("This isolated run permits only one navigation");
    this.attempted = true;
    const text = await capture(target);
    if (!text.trim() || Buffer.byteLength(text) > MAX_SNAPSHOT_BYTES) throw new Error("Empty or oversized snapshot");
    await this.assertDirectory();
    const file = await open(this.path, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, 0o600);
    try {
      await file.writeFile(text, "utf8");
      const stat = await file.stat();
      this.captured = { at: Date.now(), ino: stat.ino, dev: stat.dev, text };
    } finally {
      await file.close();
    }
    return { captured: true, bytes: Buffer.byteLength(text) };
  }

  async read() {
    await this.assertDirectory();
    const captured = this.captured;
    if (!captured || Date.now() - captured.at > MAX_AGE_MS) throw new Error("No fresh snapshot in this run");
    const file = await open(this.path, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
    try {
      const stat = await file.stat();
      if (!stat.isFile() || stat.nlink !== 1 || stat.size > MAX_SNAPSHOT_BYTES ||
          stat.ino !== captured.ino || stat.dev !== captured.dev) throw new Error("Unsafe snapshot file");
      // A bounded read avoids accepting a file that grows after stat().
      const buffer = Buffer.alloc(MAX_SNAPSHOT_BYTES + 1);
      const { bytesRead } = await file.read(buffer, 0, buffer.length, 0);
      const text = buffer.subarray(0, bytesRead).toString("utf8");
      if (bytesRead > MAX_SNAPSHOT_BYTES || text !== captured.text) throw new Error("Snapshot changed after capture");
      return text;
    } finally {
      await file.close();
    }
  }

  async dispose() {
    await rm(this.directory, { recursive: true, force: true });
  }

  private async assertDirectory() {
    const stat = await lstat(this.directory);
    if (!stat.isDirectory() || stat.isSymbolicLink()) throw new Error("Unsafe snapshot directory");
  }
}
