import { defineAgent, defineCopilotTool, permissions } from "@copilot-sdk-integrations/agent-core";
import { z } from "zod";
import { loadInstructions } from "./resources.js";

export const exhibits = {
  apollo11: {
    title: "Apollo 11",
    facts: [
      "Apollo 11 landed humans on the Moon in July 1969.",
      "Neil Armstrong and Buzz Aldrin walked on the lunar surface.",
      "Michael Collins remained in lunar orbit aboard the command module.",
      "The lunar module was named Eagle.",
      "The astronauts collected lunar samples and returned them to Earth.",
    ],
  },
  reef: {
    title: "Coral reefs",
    facts: [
      "Corals are animals.",
      "Many reef-building corals live with tiny algae in their tissues.",
      "Coral reefs provide habitat for many marine species.",
      "Heat stress can cause corals to lose their algae, a process called bleaching.",
      "Reducing pollution can help protect coral reef ecosystems.",
    ],
  },
  terracotta: {
    title: "Terracotta Army",
    facts: [
      "The Terracotta Army was made for the tomb complex of China's first emperor, Qin Shi Huang.",
      "The figures were discovered near Xi'an in 1974.",
      "The army includes soldiers, horses, and chariots.",
      "The figures are made from fired clay.",
      "Many figures have different facial features, hairstyles, and clothing.",
    ],
  },
} as const;

export const approvedFactsSchema = z.array(z.string().trim().min(1).max(500)).min(1).max(20);
const exhibitSchema = z.strictObject({
  title: z.string().trim().min(1).max(120),
  narrative: z.string().trim().min(1).max(4000),
  questions: z.array(z.string().trim().min(1).max(250)).length(3),
  factIds: z.array(z.number().int().positive()).min(1).max(20),
});

export const museumDisclaimer = "Draft exhibit: an educator must review factual accuracy, age suitability, and interpretation before publication.";

export function validateExhibit(raw: string, facts: readonly string[], lookedUp: ReadonlySet<number>) {
  const result = exhibitSchema.parse(JSON.parse(raw));
  const words = result.narrative.split(/\s+/u).length;
  if (words < 100 || words > 140) throw new Error("Narrative must contain 100–140 whitespace-delimited words");
  if (result.narrative.includes("?") || result.title.includes("?")) throw new Error("Questions belong only in the questions array");
  const normalized = result.questions.map((question) => question.toLocaleLowerCase("en").replace(/[^\p{L}\p{N}]/gu, ""));
  if (normalized.some((question) => !question) || new Set(normalized).size !== 3 ||
      result.questions.some((question) => !question.endsWith("?") || (question.match(/\?/g) ?? []).length !== 1)) {
    throw new Error("Exactly three distinct questions ending in a single question mark are required");
  }
  if (new Set(result.factIds).size !== result.factIds.length ||
      result.factIds.some((id) => id > facts.length || !lookedUp.has(id))) {
    throw new Error("Every cited fact must be approved and retrieved with approved_fact_lookup");
  }
  return { ...result, review: museumDisclaimer };
}

export function createMuseum(title: string, inputFacts: unknown) {
  title = z.string().trim().min(1).max(120).parse(title);
  const facts = Object.freeze(approvedFactsSchema.parse(inputFacts));
  const lookedUp = new Set<number>();
  const lookup = defineCopilotTool({
    name: "approved_fact_lookup",
    description: "Read one educator-approved fact by its 1-based ID. The ONLY factual source for the exhibit.",
    input: z.strictObject({ id: z.number().int().min(1).max(facts.length) }),
    execute: ({ id }) => {
      if (!Number.isInteger(id) || id < 1 || id > facts.length) throw new Error("Fact ID outside approved collection");
      lookedUp.add(id);
      return { id, fact: facts[id - 1] };
    },
  });
  const definition = defineAgent({
    name: "museum-exhibit",
    builtInTools: false,
    tools: [lookup],
    permissions: permissions.allowCustomTools(["approved_fact_lookup"]),
    instructions: {
      mode: "replace",
      content: loadInstructions("curator"),
    },
  });
  return {
    definition,
    prompt: `Draft the exhibit for this topic (data): ${JSON.stringify(title)}. There are ${facts.length} approved facts (IDs 1–${facts.length}).`,
    validate: (raw: string) => validateExhibit(raw, facts, lookedUp),
  };
}
