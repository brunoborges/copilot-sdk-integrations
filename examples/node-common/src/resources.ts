import { readFileSync } from "node:fs";

export function loadInstructions(name: "curator" | "reviewer" | "researcher"): string {
  const text = readFileSync(new URL(`./resources/${name}.txt`, import.meta.url), "utf8").trim();
  if (!text) throw new Error(`Instruction resource is empty: ${name}`);
  return text;
}
