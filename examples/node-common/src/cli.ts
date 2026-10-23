import { readFile, open } from "node:fs/promises";
import { constants } from "node:fs";
import { createMuseum, exhibits } from "./museum.js";
import { createAccessibility } from "./accessibility.js";
import { SnapshotStore } from "./snapshot.js";
import { createResearch } from "./research.js";
import { renderHtml } from "./html.js";

export async function prepareCommand(args: string[]) {
  const htmlIndex = args.indexOf("--html");
  const outputHtml = htmlIndex >= 0 ? args[htmlIndex + 1] : undefined;
  if (htmlIndex >= 0 && (!outputHtml || htmlIndex !== args.length - 2)) throw new Error("--html <new-file.html> must be the final option");
  const commandArgs = htmlIndex >= 0 ? args.slice(0, htmlIndex) : args;
  const [mode, value, factFile] = commandArgs;
  if (mode === "museum" && value && commandArgs.length <= 3) {
    const exhibit = Object.hasOwn(exhibits, value) ? exhibits[value as keyof typeof exhibits] : undefined;
    if (!exhibit && !factFile) throw new Error("Choose apollo11, reef, terracotta, or supply a topic and facts JSON file");
    let facts: unknown = exhibit?.facts;
    if (factFile) {
      const bytes = await readFile(factFile);
      if (bytes.length > 32_768) throw new Error("Fact file exceeds 32 KiB");
      facts = JSON.parse(bytes.toString("utf8"));
    }
    return { ...createMuseum(exhibit?.title ?? value, facts), outputHtml, dispose: async () => {} };
  }
  if (mode === "research" && value && commandArgs.length === 2) {
    return { ...createResearch(value), outputHtml, dispose: async () => {} };
  }
  if (mode === "accessibility" && value && commandArgs.length === 2) {
    const store = await SnapshotStore.create();
    try {
      return { ...createAccessibility(value, store), outputHtml, target: value, dispose: () => store.dispose() };
    } catch (error) {
      await store.dispose();
      throw error;
    }
  }
  throw new Error("Usage: museum <apollo11|reef|terracotta> OR museum <topic> <facts.json> OR accessibility <exact-url> OR research <topic> [--html <new-file.html>]");
}

export async function completeCommand(command: Awaited<ReturnType<typeof prepareCommand>>, raw: string) {
  const result = command.validate(raw);
  if (command.outputHtml) {
    const file = await open(command.outputHtml, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, 0o600);
    try {
      await file.writeFile(renderHtml(result, "target" in command ? command.target : undefined));
    } finally {
      await file.close();
    }
  }
  return result;
}
