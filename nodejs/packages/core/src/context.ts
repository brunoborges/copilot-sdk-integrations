import { AsyncLocalStorage } from "node:async_hooks";
import type { CopilotContext } from "./types.js";

const storage = new AsyncLocalStorage<CopilotContext>();

/** The current turn's context, or `undefined` outside a Copilot turn or request scope. */
export function getCopilotContext(): CopilotContext | undefined {
  return storage.getStore();
}

/** Run `fn` with `context` merged over the current context. */
export function runWithCopilotContext<T>(context: Partial<CopilotContext>, fn: () => T): T {
  const parent = storage.getStore();
  const merged: CopilotContext = {
    ...parent,
    ...context,
    attributes: { ...(parent?.attributes ?? {}), ...(context.attributes ?? {}) },
  };
  return storage.run(merged, fn);
}

/** Bind `fn` to the current context, for callbacks invoked outside the async scope (e.g. JSON-RPC handlers). */
export function bindCopilotContext<A extends unknown[], R>(fn: (...args: A) => R): (...args: A) => R {
  const captured = storage.getStore();
  if (!captured) return fn;
  return (...args: A) => storage.run(captured, () => fn(...args));
}
