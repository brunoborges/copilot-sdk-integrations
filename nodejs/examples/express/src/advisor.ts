import { defineAgent, defineCopilotTool, permissions, type CopilotAgentDefinition } from "@copilot-sdk-integrations/core";
import { tool } from "ai";
import { z } from "zod";

/** Local reference data, like the Spring Boot example's `ReferenceTools`. */
export const REFERENCE: Record<string, string> = {
  "event loop": "The event loop runs JavaScript callbacks on a single thread and hands I/O to libuv.",
  libuv: "libuv is the C library that gives Node.js its asynchronous, cross-platform I/O.",
  streams: "Streams process data piece by piece with backpressure instead of buffering it all in memory.",
  "worker threads": "Worker threads run JavaScript in parallel isolates that communicate by message passing.",
  "require(esm)": "Since Node.js 20.19 and 22.12, CommonJS code can require() synchronous ES modules.",
};

export const describeTerm = defineCopilotTool({
  name: "describeTerm",
  description: "Look up a short definition of a Node.js term from local reference data",
  input: z.object({ term: z.string().min(1).describe("Node.js term to describe") }),
  execute: ({ term }) => {
    const definition = REFERENCE[term.toLowerCase()];
    if (!definition) throw new Error(`Unknown reference term: ${term}`);
    return { term, definition };
  },
});

export interface Note {
  title: string;
  body: string;
}

/** Per-user notes, keyed by the principal of the turn. */
export class Notebook {
  private readonly notes = new Map<string, Note[]>();

  add(user: string, note: Note): number {
    const list = this.notes.get(user) ?? [];
    list.push(note);
    this.notes.set(user, list);
    return list.length;
  }

  list(user: string): Note[] {
    return [...(this.notes.get(user) ?? [])];
  }
}

/**
 * The Node.js advisor. The policy approves only this app's own tools (built-in shell, file and URL
 * tools are denied). `saveNote` is an AI SDK `tool()` with `needsApproval`, which turns that approval
 * into a request the user answers in the UI (`addToolApprovalResponse`).
 */
export function createAdvisor(notebook: Notebook, user: () => string): CopilotAgentDefinition {
  const saveNote = tool({
    description: "Save a note to the user's notebook",
    inputSchema: z.object({ title: z.string().min(1), body: z.string().min(1) }),
    needsApproval: true,
    execute: async (note) => ({ saved: true, count: notebook.add(user(), note) }),
  });

  return defineAgent({
    name: "node-advisor",
    instructions:
      "You are a concise Node.js advisor. Use describeTerm for definitions and saveNote when the user asks to remember something.",
    tools: [describeTerm, { saveNote }],
    permissions: permissions.allowCustomTools(["describeTerm", "saveNote"]),
  });
}
