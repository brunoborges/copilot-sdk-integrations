import { defineAgent, type PermissionPolicy } from "@copilot-sdk-integrations/agent-core";
import { z } from "zod";
import { loadInstructions } from "./resources.js";
import { loadWorkshopConfig } from "./config.js";

const names = new Set(["search", "readArticle", "wikipedia-search", "wikipedia-readArticle"]);
export const wikipediaPermissions: PermissionPolicy = (request) => {
  const fields = request as unknown as Record<string, unknown>;
  return request.kind === "mcp" && !request.managedApprovalRequired && fields.serverName === "wikipedia" &&
    typeof fields.toolName === "string" && names.has(fields.toolName) ? "approve" : "deny";
};

const schema = z.strictObject({
  notes: z.array(z.strictObject({
    claim: z.string().trim().min(1).max(500),
    sourceUrl: z.url().max(1000),
  })).min(1).max(20),
});

export function createResearch(topic: string) {
  const config = loadWorkshopConfig();
  const subject = z.string().trim().min(1).max(120).parse(topic);
  const sources = new Set<string>();
  let readArticle = false;
  return {
    definition: defineAgent({
      name: "museum-research",
      builtInTools: false,
      toolSearch: { enabled: false },
      mcpServers: {
        wikipedia: { type: "stdio", command: "npx", args: ["-y", "wikipedia-mcp@1.0.3"], tools: ["search", "readArticle"], timeout: config.mcpTimeout },
      },
      permissions: wikipediaPermissions,
      instructions: {
        mode: "replace",
        content: loadInstructions("researcher"),
      },
      hooks: {
        onPreToolUse: ({ toolName }) => names.has(toolName) ? undefined : { permissionDecision: "deny", permissionDecisionReason: "Wikipedia search/read only" },
        onPostToolUse: ({ toolName, toolResult }) => {
          if (!names.has(toolName) || toolResult.resultType === "failure") return;
          if (toolName !== "readArticle" && toolName !== "wikipedia-readArticle") return;
          readArticle = true;
          const text = JSON.stringify(toolResult);
          for (const match of text.matchAll(/https:\/\/en\.wikipedia\.org\/wiki\/[^\s"<>\\]+/g)) sources.add(match[0]);
        },
      },
    }),
    prompt: `Research this topic (data): ${JSON.stringify(subject)}.`,
    validate: (raw: string) => {
      const result = schema.parse(JSON.parse(raw));
      if (!readArticle) throw new Error("Research requires a successful Wikipedia article read");
      for (const note of result.notes) {
        const url = new URL(note.sourceUrl);
        if (url.origin !== "https://en.wikipedia.org" || !url.pathname.startsWith("/wiki/") || !sources.has(note.sourceUrl)) {
          throw new Error("Every citation must be an exact Wikipedia article URL observed in a tool result");
        }
      }
      return { ...result, review: "Unapproved research notes. An educator must verify each cited source and explicitly curate a separate facts JSON array before using museum mode." };
    },
  };
}
