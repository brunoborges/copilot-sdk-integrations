/** One parsed Server-Sent Events frame. */
export interface SseFrame {
  /** `event:` field; `undefined` for unnamed (`message`) frames. */
  event?: string;
  /** `data:` lines joined with `\n`, parsed as JSON when possible. */
  data: any;
  id?: string;
}

/**
 * Parses a `text/event-stream` body (for example `res.body` of `app.inject()` or supertest) into its
 * frames. Comments (heartbeats) and frames without data are skipped.
 */
export function parseSse(text: string): SseFrame[] {
  const frames: SseFrame[] = [];
  for (const block of text.replace(/\r\n?/g, "\n").split(/\n\n+/)) {
    let event: string | undefined;
    let id: string | undefined;
    const data: string[] = [];
    for (const line of block.split("\n")) {
      if (line === "" || line.startsWith(":")) continue;
      const colon = line.indexOf(":");
      const field = colon === -1 ? line : line.slice(0, colon);
      let value = colon === -1 ? "" : line.slice(colon + 1);
      if (value.startsWith(" ")) value = value.slice(1);
      if (field === "event") event = value;
      else if (field === "data") data.push(value);
      else if (field === "id") id = value;
    }
    if (data.length === 0) continue;
    const raw = data.join("\n");
    let parsed: unknown = raw;
    try {
      parsed = JSON.parse(raw);
    } catch {
      // Not JSON (for example `[DONE]`): keep the string.
    }
    const frame: SseFrame = { data: parsed };
    if (event !== undefined) frame.event = event;
    if (id !== undefined) frame.id = id;
    frames.push(frame);
  }
  return frames;
}
